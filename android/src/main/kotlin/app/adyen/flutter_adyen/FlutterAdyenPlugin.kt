package app.adyen.flutter_adyen

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.adyen.checkout.card.CardConfiguration
import com.adyen.checkout.components.model.PaymentMethodsApiResponse
import com.adyen.checkout.components.model.payments.Amount
import com.adyen.checkout.components.model.payments.request.PaymentComponentData
import com.adyen.checkout.components.model.payments.request.PaymentMethodDetails
import com.adyen.checkout.core.api.Environment
import com.adyen.checkout.dropin.DropIn
import com.adyen.checkout.dropin.DropInConfiguration
import com.adyen.checkout.dropin.service.DropInService
import com.adyen.checkout.dropin.service.DropInServiceResult
import com.adyen.checkout.redirect.RedirectComponent
import com.adyen.checkout.googlepay.GooglePayConfiguration
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.squareup.moshi.Moshi
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.plugin.common.PluginRegistry
import io.flutter.plugin.common.PluginRegistry.Registrar
import okhttp3.MediaType
import okhttp3.RequestBody
import org.json.JSONObject
import java.io.IOException
import java.io.Serializable
import com.google.gson.reflect.TypeToken;
import io.flutter.Log
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.BinaryMessenger
import java.util.*
import kotlin.collections.HashMap
import kotlin.jvm.Throws

class FlutterAdyenPlugin :
        MethodCallHandler, PluginRegistry.ActivityResultListener, FlutterPlugin, ActivityAware {

    private var methodChannel: MethodChannel? = null

    private var activity: Activity? = null
    private var activityBinding: ActivityPluginBinding? = null

    var flutterResult: Result? = null

    companion object {

        const val CHANNEL_NAME = "flutter_adyen"

        /**
         * For EmbeddingV1
         */
        @JvmStatic
        fun registerWith(registrar: Registrar) {
            FlutterAdyenPlugin().apply {
                onAttachedToEngine(registrar.messenger())
                activity = registrar.activity()
                addActivityResultListener(registrar)
            }
        }
    }

    override fun onMethodCall(call: MethodCall, res: Result) {
        when (call.method) {
            "openDropIn" -> {

                if (activity == null) {
                    res.error("1",
                            "Activity is null",
                            "The activity is probably not attached")
                    return
                }

                val nonNullActivity = activity!!

                val additionalData = call.argument<Map<String, String>>("additionalData") ?: emptyMap()
                val headers = call.argument<Map<String, String>>("headers") ?: emptyMap()
                val paymentMethods = call.argument<String>("paymentMethods")
                val baseUrl = call.argument<String>("baseUrl")
                val clientKey = call.argument<String>("clientKey")
                val amount = call.argument<String>("amount")
                val currency = call.argument<String>("currency")
                val env = call.argument<String>("environment")
                val showStorePaymentField = call.argument<Boolean>("showStorePaymentField")
                val lineItem = call.argument<Map<String, String>>("lineItem")
                val shopperReference = call.argument<String>("shopperReference")

                @Suppress("NULLABILITY_MISMATCH_BASED_ON_JAVA_ANNOTATIONS")
                val lineItemString = JSONObject(lineItem).toString()
                val additionalDataString = JSONObject(additionalData).toString()
                val headersString = JSONObject(headers).toString()
                val localeString = call.argument<String>("locale") ?: "el_GR"
                val countryCode = localeString.split("_").last()
                val languageCode = localeString.split("_").first()

                /*
                Log.e("[Flutter Adyen]", "Client Key from Flutter: $clientKey")
                Log.e("[Flutter Adyen]", "Environment from Flutter: $env")
                Log.e("[Flutter Adyen]", "Locale String from Flutter: $localeString")
                Log.e("[Flutter Adyen]", "Locale String from Flutter: $paymentMethods")
                Log.e("[Flutter Adyen]", "Country Code from Flutter: $countryCode")
                Log.e("[Flutter Adyen]", "Base URL from Flutter: $baseUrl")
                Log.e("[Flutter Adyen]", "Currency from Flutter: $currency")
                Log.e("[Flutter Adyen]", "Shopper Reference from Flutter: $shopperReference")
                */

                val environment = when (env) {
                    "LIVE_US" -> Environment.UNITED_STATES
                    "LIVE_AUSTRALIA" -> Environment.AUSTRALIA
                    "LIVE_EUROPE" -> Environment.EUROPE
                    else -> Environment.TEST
                }

                // Log.e("[Flutter Adyen] ENVIRONMENT", "Resolved environment: $environment")

                try {
                    val jsonObject = JSONObject(paymentMethods ?: "")
                    val paymentMethodsApiResponse = PaymentMethodsApiResponse.SERIALIZER.deserialize(jsonObject)
                    val shopperLocale = Locale.forLanguageTag(languageCode)
                    // val shopperLocale = Locale("el", "GR")
                    // val shopperLocale = if (LocaleUtil.isValidLocale(locale)) locale else LocaleUtil.getLocale(nonNullActivity)
                    // Log.e("[Flutter Adyen] SHOPPER LOCALE", "Shopper Locale from localeString $localeString: $shopperLocale")
                    val cardConfiguration = CardConfiguration.Builder(nonNullActivity, clientKey!!)
                            .setHolderNameRequired(true)
                            .setShowStorePaymentField(showStorePaymentField ?: false)
                            .setShopperLocale(shopperLocale)
                            .setEnvironment(environment)
                            .build()

                    val sharedPref = nonNullActivity.getSharedPreferences("ADYEN", Context.MODE_PRIVATE)
                    with(sharedPref.edit()) {
                        remove("AdyenResultCode")
                        putString("baseUrl", baseUrl)
                        putString("amount", "$amount")
                        putString("countryCode", countryCode)
                        putString("currency", currency)
                        putString("lineItem", lineItemString)
                        putString("additionalData", additionalDataString)
                        putString("headers", headersString)
                        putString("shopperReference", shopperReference)
                        commit()
                    }

                    val amountToPay = Amount()
                    amountToPay.currency = currency
                    amountToPay.value = amount!!.toInt()

                    val googlePayConfig = GooglePayConfiguration.Builder(nonNullActivity, clientKey)
                            .setAmount(amountToPay)
                            .setEnvironment(environment)
                            .build()

                    val dropInConfigurationBeforeBuild = DropInConfiguration.Builder(nonNullActivity, AdyenDropinService::class.java, clientKey)
                            .addCardConfiguration(cardConfiguration)
                            .setShopperLocale(shopperLocale)
                            .setEnvironment(environment)
                            .addGooglePayConfiguration(googlePayConfig)
                    
                    if (amountToPay.value > 0) {
                      var dropInConfiguration = dropInConfigurationBeforeBuild
                            .setAmount(amountToPay)
                            .build()
                      DropIn.startPayment(nonNullActivity, paymentMethodsApiResponse, dropInConfiguration)
                    } else {
                      var dropInConfiguration = dropInConfigurationBeforeBuild
                            .build()
                      DropIn.startPayment(nonNullActivity, paymentMethodsApiResponse, dropInConfiguration)
                    }

                    flutterResult = res
                } catch (e: Throwable) {
                    res.error("PAYMENT_ERROR", "${e.printStackTrace()}", "")
                }
            }
            else -> {
                res.notImplemented()
            }
        }
    }

    //region lifecycle
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (activity == null) return false

        val sharedPref = activity!!.getSharedPreferences("ADYEN", Context.MODE_PRIVATE)
        val storedResultCode = sharedPref.getString("AdyenResultCode", "PAYMENT_CANCELLED")
        flutterResult?.success(storedResultCode)
        flutterResult = null
        return true
    }

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        onAttachedToEngine(binding.binaryMessenger)
    }

    private fun onAttachedToEngine(messenger: BinaryMessenger) {
        this.methodChannel = MethodChannel(messenger, CHANNEL_NAME)
        this.methodChannel?.setMethodCallHandler(this)
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        unbindActivityBinding()
        this.methodChannel = null
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        bindActivityBinding(binding)
    }

    override fun onDetachedFromActivityForConfigChanges() {
        unbindActivityBinding()
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        bindActivityBinding(binding)
    }

    override fun onDetachedFromActivity() {
        unbindActivityBinding()
    }

    private fun bindActivityBinding(binding: ActivityPluginBinding) {
        this.activity = binding.activity
        this.activityBinding = binding
        addActivityResultListener(binding)
    }

    private fun unbindActivityBinding() {
        activityBinding?.removeActivityResultListener(this)
        this.activity = null
        this.activityBinding = null
    }

    private fun addActivityResultListener(activityBinding: ActivityPluginBinding) {
        activityBinding.addActivityResultListener(this)
    }

    private fun addActivityResultListener(registrar: PluginRegistry.Registrar) {
        registrar.addActivityResultListener(this)
    }
    //endregion
}

/**
 * This is just an example on how to make network calls on the [DropInService].
 * You should make the calls to your own servers and have additional data or processing if necessary.
 */
// @Throws(JsonSyntaxException::class)
// inline fun <reified T> Gson.fromJson(json: String): T? = fromJson<T>(json, object: TypeToken<T>() {}.type)

class AdyenDropinService : DropInService() {

    override fun makePaymentsCall(paymentComponentJson: JSONObject): DropInServiceResult {
        val sharedPref = getSharedPreferences("ADYEN", Context.MODE_PRIVATE)
        val baseUrl = sharedPref.getString("baseUrl", "UNDEFINED_STR")
        val amount = sharedPref.getString("amount", "UNDEFINED_STR")
        val currency = sharedPref.getString("currency", "UNDEFINED_STR")
        val countryCode = sharedPref.getString("countryCode", "GR")
        val lineItemString = sharedPref.getString("lineItem", "UNDEFINED_STR")
        val additionalDataString = sharedPref.getString("additionalData", "UNDEFINED_STR")
        val headersString = sharedPref.getString("headers", null)
        val uuid: UUID = UUID.randomUUID()
        val reference: String = uuid.toString()
        val shopperReference = sharedPref.getString("shopperReference", null)

        val moshi = Moshi.Builder().build()
        val jsonAdapter = moshi.adapter(LineItem::class.java)
        val lineItem: LineItem? = jsonAdapter.fromJson(lineItemString ?: "")

        val gson = Gson()
        val additionalData = gson.fromJson<Map<String, String>>(additionalDataString ?: "", object: TypeToken<HashMap<String, String>>() {}.type) ?: emptyMap()

        val serializedPaymentComponentData = PaymentComponentData.SERIALIZER.deserialize(paymentComponentJson)

        if (serializedPaymentComponentData.paymentMethod == null)
            return DropInServiceResult.Error(errorMessage = "Empty payment data")

        val paymentsRequest = createPaymentsRequest(
                context = this@AdyenDropinService, lineItem, serializedPaymentComponentData,
                amount = amount ?: "",
                currency = currency ?: "",
                reference = reference,
                shopperReference = shopperReference,
                countryCode = countryCode ?: "GR",
                additionalData = additionalData)
        val paymentsRequestJson = serializePaymentsRequest(paymentsRequest)

        val requestBody = RequestBody.create(MediaType.parse("application/json"), paymentsRequestJson.toString())

        val headersGson = Gson()
        val headers: HashMap<String, String> = headersGson.fromJson<HashMap<String, String>>(headersString ?: "", object: TypeToken<HashMap<String, String>>() {}.type) ?: HashMap()

        val call = getService(headers, baseUrl ?: "").payments(requestBody)
        call.request().headers()
        return try {
            val response = call.execute()
            val paymentsResponse = response.body()

            if (response.isSuccessful && paymentsResponse != null) {
                if (paymentsResponse.action != null) {
                    with(sharedPref.edit()) {
                        putString("AdyenResultCode", paymentsResponse.action.toString())
                        commit()
                    }
                    DropInServiceResult.Action(paymentsResponse.action)
                } else {
                    if (paymentsResponse.resultCode != null) {
                      with(sharedPref.edit()) {
                            putString("AdyenResultCode", paymentsResponse.resultCode)
                            commit()
                      }

                      if (paymentsResponse.resultCode == "Authorised") {
                       DropInServiceResult.Finished(paymentsResponse.resultCode)
                      } else {
                        DropInServiceResult.Error(paymentsResponse.localizedErrorMessage ?: paymentsResponse.resultCode);
                      }
                    } else {
                        with(sharedPref.edit()) {
                            putString("AdyenResultCode", paymentsResponse.resultCode ?: "Something went wrong, please try again")
                            commit()
                        }
                       DropInServiceResult.Finished(paymentsResponse.resultCode ?: "Something went wrong, please try again")
                    }
                }
            } else {
                with(sharedPref.edit()) {
                    putString("AdyenResultCode", "ERROR")
                    commit()
                }
                DropInServiceResult.Error(errorMessage = "Something went wrong, please try again")
            }
        } catch (e: IOException) {
            with(sharedPref.edit()) {
                putString("AdyenResultCode", "ERROR")
                commit()
            }
            DropInServiceResult.Error(errorMessage = "Something went wrong. Please try again and if the problem persists, contact customer support.")
        }
    }

    override fun makeDetailsCall(actionComponentJson: JSONObject): DropInServiceResult {
        val sharedPref = getSharedPreferences("ADYEN", Context.MODE_PRIVATE)
        val baseUrl = sharedPref.getString("baseUrl", "UNDEFINED_STR")
        val requestBody = RequestBody.create(MediaType.parse("application/json"), actionComponentJson.toString())
        val headersString = sharedPref.getString("headers", null)
        val headersGson = Gson()
        val headers: HashMap<String, String> = headersGson.fromJson<HashMap<String, String>>(headersString ?: "", object: TypeToken<HashMap<String, String>>() {}.type) ?: HashMap()

        val call = getService(headers, baseUrl ?: "").details(requestBody)
        return try {
            val response = call.execute()
            val detailsResponse = response.body()
            if (response.isSuccessful && detailsResponse != null) {
                if (detailsResponse.action != null) {
                    with(sharedPref.edit()) {
                        putString("AdyenResultCode", detailsResponse.action.toString())
                        commit()
                    }
                    DropInServiceResult.Action(detailsResponse.action)
                } else {
                    if (detailsResponse.resultCode != null) {
                      with(sharedPref.edit()) {
                            putString("AdyenResultCode", detailsResponse.resultCode)
                            commit()
                      }

                      if (detailsResponse.resultCode == "Authorised") {
                       DropInServiceResult.Finished(detailsResponse.resultCode)
                      } else {
                        DropInServiceResult.Error(detailsResponse.localizedErrorMessage ?: detailsResponse.resultCode);
                      }
                    } else {
                        with(sharedPref.edit()) {
                            putString("AdyenResultCode", detailsResponse.resultCode ?: "Something went wrong, please try again")
                            commit()
                        }
                       DropInServiceResult.Finished(detailsResponse.resultCode ?: "Something went wrong, please try again")
                    }
                }
            } else {
                with(sharedPref.edit()) {
                    putString("AdyenResultCode", "ERROR")
                    commit()
                }
                DropInServiceResult.Error(errorMessage = "Something went wrong, please try again")
            }
        } catch (e: IOException) {
            with(sharedPref.edit()) {
                putString("AdyenResultCode", "ERROR")
                commit()
            }
            DropInServiceResult.Error(errorMessage =  "Something went wrong, please try again")
        }
    }
}

fun createPaymentsRequest(context: Context, lineItem: LineItem?,
                          paymentComponentData: PaymentComponentData<out PaymentMethodDetails>,
                          amount: String, currency: String,
                          reference: String, shopperReference: String?,
                          countryCode: String,
                          additionalData: Map<String, String>): PaymentsRequest {
    @Suppress("UsePropertyAccessSyntax")
    return PaymentsRequest(
            payment = Payment(paymentComponentData.getPaymentMethod() as PaymentMethodDetails,
                    countryCode,
                    paymentComponentData.isStorePaymentMethodEnable,
                    getAmount(amount, currency),
                    reference,
                    RedirectComponent.getReturnUrl(context),
                    lineItems = listOf(lineItem),
                    shopperReference = shopperReference),
            additionalData = additionalData
    )
}

private fun getAmount(amount: String, currency: String) = createAmount(amount.toInt(), currency)

fun createAmount(value: Int, currency: String): Amount {
    val amount = Amount()
    amount.currency = currency
    amount.value = value
    return amount
}

//region data classes
data class Payment(
        val paymentMethod: PaymentMethodDetails,
        val countryCode: String = "GR",
        val storePaymentMethod: Boolean,
        val amount: Amount,
        val reference: String,
        val returnUrl: String,
        val channel: String = "Android",
        val lineItems: List<LineItem?>,
        val additionalData: AdditionalData = AdditionalData(allow3DS2 = "true"),
        val shopperReference: String?
): Serializable

data class PaymentsRequest(
        val payment: Payment,
        val additionalData: Map<String, String>
): Serializable

data class LineItem(
        val id: String,
        val description: String
): Serializable

data class AdditionalData(val allow3DS2: String = "true")
//endregion

private fun serializePaymentsRequest(paymentsRequest: PaymentsRequest): JSONObject {
    val gson = Gson()
    val jsonString = gson.toJson(paymentsRequest)
    val request = JSONObject(jsonString)
    print(request)
    return request
}
