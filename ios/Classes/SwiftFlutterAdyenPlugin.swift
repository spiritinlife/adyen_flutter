import Flutter
import UIKit
import Adyen
import Adyen3DS2
import Foundation
import AdyenNetworking
import PassKit

 struct PaymentError: Error {
     public var errorDescription: String?

     public init(errorDescription: String? = nil) {
         self.errorDescription = errorDescription
     }
 }

struct PaymentCancelled: Error {

}
public class SwiftFlutterAdyenPlugin: NSObject, FlutterPlugin {

    public static func register(with registrar: FlutterPluginRegistrar) {
        let channel = FlutterMethodChannel(name: "flutter_adyen", binaryMessenger: registrar.messenger())
        let instance = SwiftFlutterAdyenPlugin()
        registrar.addMethodCallDelegate(instance, channel: channel)
    }

    var dropInComponent: DropInComponent?
    var baseURL: String?
    var merchantAccount: String?
    var merchantIdentifier: String?
    var clientKey: String?
    var currency: String?
    var amount: String?
    var returnUrl: String?
    var reference: String?
    var mResult: FlutterResult?
    var topController: UIViewController?
    var environment: String?
    var shopperReference: String?
    var lineItemJson: [String: String]?
    var shopperLocale: String?
    var additionalData: [String: String]?
    var headers: [String: String]?
    var showStorePaymentField: Bool?

    public func handle(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
        guard call.method.elementsEqual("openDropIn") else { return }

        let arguments = call.arguments as? [String: Any]
        let paymentMethodsResponse = arguments?["paymentMethods"] as? String
        baseURL = arguments?["baseUrl"] as? String
        headers = arguments?["headers"] as? [String: String]
        merchantAccount = arguments?["merchantAccount"] as? String
        merchantIdentifier = arguments?["merchantIdentifier"] as? String
        showStorePaymentField = arguments?["showStorePaymentField"] as? Bool
        additionalData = arguments?["additionalData"] as? [String: String]
        clientKey = arguments?["clientKey"] as? String
        currency = arguments?["currency"] as? String
        amount = arguments?["amount"] as? String
        lineItemJson = arguments?["lineItem"] as? [String: String]
        environment = arguments?["environment"] as? String
        reference = arguments?["reference"] as? String
        returnUrl = arguments?["returnUrl"] as? String
        shopperReference = arguments?["shopperReference"] as? String
        shopperLocale = String((arguments?["locale"] as? String)?.split(separator: "_").last ?? "GR")
        mResult = result

        guard let paymentData = paymentMethodsResponse?.data(using: .utf8),
              let paymentMethods = try? JSONDecoder().decode(PaymentMethods.self, from: paymentData) else {
            return
        }

        var ctx = Environment.test
        if(environment == "LIVE_US") {
            ctx = Environment.liveUnitedStates
        } else if (environment == "LIVE_AUSTRALIA"){
            ctx = Environment.liveAustralia
        } else if (environment == "LIVE_EUROPE"){
            ctx = Environment.liveEurope
        }

        let dropInComponentStyle = DropInComponent.Style()

        let formatter = NumberFormatter()
        formatter.generatesDecimalNumbers = true
        let amountAsDecimal = formatter.number(from: amount ?? "0") as? NSDecimalNumber ?? 0
        let summaryItems = [PKPaymentSummaryItem(label: lineItemJson?["description"] ?? "Vendora payment", amount: amountAsDecimal.dividing(by: 100), type: .final)]
        
        let applePayConfiguration = ApplePayComponent.Configuration(summaryItems: summaryItems, merchantIdentifier: merchantIdentifier ?? "")
        let amountAsInt = Int(amount ?? "0")

        let apiContext = APIContext(environment: ctx, clientKey: clientKey!)
        let configuration = DropInComponent.Configuration(apiContext: apiContext);
        configuration.localizationParameters = LocalizationParameters(tableName: shopperLocale!)
        configuration.card.showsHolderNameField = true
        configuration.card.showsStorePaymentMethodField = showStorePaymentField!
        if (merchantIdentifier != nil && !merchantIdentifier!.isEmpty) {
            configuration.applePay = applePayConfiguration
        }
        if (amountAsInt! > 0) {
            configuration.payment = Adyen.Payment(amount: Adyen.Amount(value: amountAsInt!, currencyCode: "EUR"), countryCode: shopperLocale!)
        }
        dropInComponent = DropInComponent(paymentMethods: paymentMethods, configuration: configuration, style: dropInComponentStyle)
        dropInComponent?.delegate = self

        if var topController = UIApplication.shared.keyWindow?.rootViewController, let dropIn = dropInComponent {
            self.topController = topController
            while let presentedViewController = topController.presentedViewController{
                topController = presentedViewController
            }
            topController.present(dropIn.viewController, animated: true)
        }
    }
}

extension SwiftFlutterAdyenPlugin: DropInComponentDelegate {

    public func didComplete(from component: DropInComponent) {
        component.stopLoadingIfNeeded()
    }

    public func didCancel(component: PaymentComponent, from dropInComponent: DropInComponent) {
        self.didFail(with: PaymentCancelled(), from: dropInComponent)
    }

    public func didSubmit(_ data: PaymentComponentData, for paymentMethod: PaymentMethod, from component: DropInComponent) {
        guard let baseURL = baseURL, let url = URL(string: baseURL + "payments") else { return }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        for (key, value) in headers! {
          request.setValue(value, forHTTPHeaderField: key)
        }
        request.addValue("application/json", forHTTPHeaderField: "Content-Type")

        let amountAsInt = Int(amount ?? "0")
        // prepare json data
        let paymentMethod = data.paymentMethod.encodable

        guard let lineItem = try? JSONDecoder().decode(LineItem.self, from: JSONSerialization.data(withJSONObject: lineItemJson ?? ["":""]) ) else{ self.didFail(with: PaymentError(), from: component)
            return
        }

        let paymentRequest = PaymentRequest(
            payment: Payment(
                paymentMethod: paymentMethod,
                lineItem: lineItem,
                currency: currency ?? "",
                merchantAccount: merchantAccount ?? "",
                reference: reference,
                amount: amountAsInt ?? 0,
                returnUrl: returnUrl ?? "",
                storePayment: data.storePaymentMethod,
                shopperReference: shopperReference,
                countryCode: shopperLocale
            ),
            additionalData:additionalData ?? [String: String]()
        )

        do {
            let jsonData = try JSONEncoder().encode(paymentRequest)

            request.httpBody = jsonData
            URLSession.shared.dataTask(with: request) { data, response, error in
                if let data = data {
                    self.finish(data: data, component: component)
                }
                if error != nil {
                    self.didFail(with: PaymentError(), from: component)
                }
            }.resume()
        } catch {
            didFail(with: PaymentError(), from: component)
        }

    }

    func finish(data: Data, component: DropInComponent) {
        DispatchQueue.main.async {
            guard let response = try? JSONDecoder().decode(PaymentsResponse.self, from: data) else {
                self.didFail(with: PaymentError(), from: component)
                return
            }
            if let action = response.action {
                component.stopLoadingIfNeeded()
                component.handle(action)
            } else {
                component.stopLoadingIfNeeded()
                if response.resultCode == .authorised, let result = self.mResult {
                    result(response.resultCode.rawValue)
                    self.topController?.dismiss(animated: false, completion: nil)

                } else if (response.resultCode == .error || response.resultCode == .refused || response.resultCode == .received || response.resultCode == .pending) {
                    self.didFail(with: PaymentError(errorDescription: response.localizedErrorMessage), from: component)
                }
                else {
                    self.didFail(with: PaymentCancelled(), from: component)
                }
            }
        }
    }

    public func didProvide(_ data: ActionComponentData, from component: DropInComponent) {
        guard let baseURL = baseURL, let url = URL(string: baseURL + "payments/details") else { return }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        for (key, value) in headers! {
          request.setValue(value, forHTTPHeaderField: key)
        }
        request.addValue("application/json", forHTTPHeaderField: "Content-Type")
        let detailsRequest = DetailsRequest(paymentData: data.paymentData ?? "", details: data.details.encodable)
        do {
            let detailsRequestData = try JSONEncoder().encode(detailsRequest)
            request.httpBody = detailsRequestData
            URLSession.shared.dataTask(with: request) { data, response, error in
                if let response = response as? HTTPURLResponse {
                    if (response.statusCode != 200) {
                        self.didFail(with: PaymentError(), from: component)
                    }
                }
                if let data = data {
                    self.finish(data: data, component: component)
                }

            }.resume()
        } catch {
            self.didFail(with: PaymentError(), from: component)
        }
    }

    public func didFail(with error: Error, from component: DropInComponent) {
        DispatchQueue.main.async {
            if (error is PaymentCancelled) {
                self.mResult?("PAYMENT_CANCELLED")
            } else if let componentError = error as? ComponentError, componentError == ComponentError.cancelled {
                self.mResult?("PAYMENT_CANCELLED")
            } else {
                if (error is PaymentError) {
                    self.showAlertError(with: error as! PaymentError)
                }
                self.mResult?("PAYMENT_ERROR")
            }
            self.topController?.dismiss(animated: true, completion: nil)
        }
    }
    
    private func showAlertError(with error: PaymentError) {
        // Create new Alert
        let dialogMessage = UIAlertController(title: "", message: error.errorDescription ?? "Something went wrong", preferredStyle: .alert)
        
        // Create OK button
        let ok = UIAlertAction(title: "OK", style: .default)
        
        //Add OK button to a dialog message
        dialogMessage.addAction(ok)
        // Present Alert to
        self.topController?.dismiss(animated: false)
        self.topController?.present(dialogMessage, animated: true, completion: nil)
    }
}

struct DetailsRequest: Encodable {
    let paymentData: String
    let details: AnyEncodable
}

struct PaymentRequest : Encodable {
    let payment: Payment
    let additionalData: [String: String]
}

struct Payment : Encodable {
    let paymentMethod: AnyEncodable
    let lineItems: [LineItem]
    let channel: String = "iOS"
    let additionalData = ["allow3DS2" : "true", "executeThreeD" : "true"]
    let amount: Amount
    let reference: String?
    let returnUrl: String
    let storePaymentMethod: Bool
    let shopperReference: String?
    let countryCode: String?
    let merchantAccount: String?

    init(paymentMethod: AnyEncodable, lineItem: LineItem, currency: String, merchantAccount: String, reference: String?, amount: Int, returnUrl: String, storePayment: Bool, shopperReference: String?, countryCode: String?) {
        self.paymentMethod = paymentMethod
        self.lineItems = [lineItem]
        self.amount = Amount(currency: currency, value: amount)
        self.returnUrl = returnUrl
        self.shopperReference = shopperReference
        self.storePaymentMethod = storePayment
        self.countryCode = countryCode
        self.merchantAccount = merchantAccount
        self.reference = reference ?? UUID().uuidString
    }
}

struct LineItem: Codable {
    let id: String
    let description: String
}

struct Amount: Codable {
    let currency: String
    let value: Int
}

internal struct PaymentsResponse: Response {

    internal let resultCode: ResultCode

    internal let action: Action?
    
    internal let localizedErrorMessage: String?

    internal init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        self.resultCode = try container.decode(ResultCode.self, forKey: .resultCode)
        self.action = try container.decodeIfPresent(Action.self, forKey: .action)
        self.localizedErrorMessage = try container.decodeIfPresent(String.self, forKey: .localizedErrorMessage)
    }

    private enum CodingKeys: String, CodingKey {
        case resultCode
        case action
        case localizedErrorMessage
    }

}

internal extension PaymentsResponse {

    // swiftlint:disable:next explicit_acl
    enum ResultCode: String, Decodable {
        case authorised = "Authorised"
        case refused = "Refused"
        case pending = "Pending"
        case cancelled = "Cancelled"
        case error = "Error"
        case received = "Received"
        case redirectShopper = "RedirectShopper"
        case identifyShopper = "IdentifyShopper"
        case challengeShopper = "ChallengeShopper"
        case presentToShopper = "PresentToShopper"
    }

}
