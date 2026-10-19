package com.selim.ultragameengine

import android.app.Activity
import android.content.Context
import android.widget.Toast
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.TimeUnit

class MonetizationManager(private val context: Context) : PurchasesUpdatedListener {
    private val prefs = context.getSharedPreferences("monetization_prefs", Context.MODE_PRIVATE)
    private var rewardedAd: RewardedAd? = null
    private var billingClient: BillingClient? = null

    private val rewardedAdUnitId = "ca-app-pub-3940256099942544/5224354917"
    private val proProductId = "ultra_game_engine_pro_lifetime"

    private val _isProUnlocked = MutableStateFlow(checkAccessStatus())
    val isProUnlocked: StateFlow<Boolean> = _isProUnlocked

    init {
        MobileAds.initialize(context) {}
        loadRewardedAd()
        setupBillingClient()
    }

    fun checkAccessStatus(): Boolean {
        val isLifetimePro = prefs.getBoolean("is_lifetime_pro", false)
        val rewardExpiryTime = prefs.getLong("reward_expiry_time", 0L)
        return isLifetimePro || System.currentTimeMillis() < rewardExpiryTime
    }

    fun getRemainingRewardMinutes(): Long {
        if (prefs.getBoolean("is_lifetime_pro", false)) return 999999L
        val expiry = prefs.getLong("reward_expiry_time", 0L)
        val diff = expiry - System.currentTimeMillis()
        return if (diff > 0L) TimeUnit.MILLISECONDS.toMinutes(diff) else 0L
    }

    private fun loadRewardedAd() {
        val adRequest = AdRequest.Builder().build()
        RewardedAd.load(context, rewardedAdUnitId, adRequest, object : RewardedAdLoadCallback() {
            override fun onAdFailedToLoad(adError: LoadAdError) {
                rewardedAd = null
            }
            override fun onAdLoaded(ad: RewardedAd) {
                rewardedAd = ad
            }
        })
    }

    fun showRewardedAdFor3Hours(activity: Activity, onUnlocked: () -> Unit) {
        val ad = rewardedAd
        if (ad != null) {
            ad.show(activity) {
                val threeHoursMs = TimeUnit.HOURS.toMillis(3)
                prefs.edit().putLong("reward_expiry_time", System.currentTimeMillis() + threeHoursMs).apply()
                _isProUnlocked.value = true
                Toast.makeText(context, "Ultra Motor 3 Saatliğine Aktif Edildi!", Toast.LENGTH_LONG).show()
                loadRewardedAd()
                onUnlocked()
            }
        } else {
            val thirtyMinMs = TimeUnit.MINUTES.toMillis(30)
            prefs.edit().putLong("reward_expiry_time", System.currentTimeMillis() + thirtyMinMs).apply()
            _isProUnlocked.value = true
            Toast.makeText(context, "Reklam hazır değil, 30 Dk Ücretsiz Ultra Motor tanımlandı!", Toast.LENGTH_SHORT).show()
            loadRewardedAd()
            onUnlocked()
        }
    }

    private fun setupBillingClient() {
        billingClient = BillingClient.newBuilder(context)
            .setListener(this)
            .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
            .build()

        billingClient?.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    restorePurchases()
                }
            }
            override fun onBillingServiceDisconnected() {}
        })
    }

    fun launchProPurchaseFlow(activity: Activity) {
        val client = billingClient
        if (client == null || !client.isReady) {
            Toast.makeText(context, "Google Play Mağaza bağlantısı bekleniyor...", Toast.LENGTH_SHORT).show()
            return
        }

        val productList = listOf(
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(proProductId)
                .setProductType(BillingClient.ProductType.INAPP)
                .build()
        )
        val params = QueryProductDetailsParams.newBuilder().setProductList(productList).build()

        client.queryProductDetailsAsync(params) { billingResult, productDetailsList ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK && productDetailsList.isNotEmpty()) {
                val flowParams = BillingFlowParams.newBuilder()
                    .setProductDetailsParamsList(
                        listOf(
                            BillingFlowParams.ProductDetailsParams.newBuilder()
                                .setProductDetails(productDetailsList[0])
                                .build()
                        )
                    ).build()
                client.launchBillingFlow(activity, flowParams)
            } else {
                Toast.makeText(context, "Play Console'da '$proProductId' ürünü tanımlandığında aktif olacaktır.", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onPurchasesUpdated(billingResult: BillingResult, purchases: MutableList<Purchase>?) {
        if (billingResult.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
            for (purchase in purchases) {
                if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
                    prefs.edit().putBoolean("is_lifetime_pro", true).apply()
                    _isProUnlocked.value = true
                    Toast.makeText(context, "Ömür Boyu PRO Sürüm Aktif Edildi!", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun restorePurchases() {
        val params = QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build()
        billingClient?.queryPurchasesAsync(params) { result, purchases ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                val hasPro = purchases.any { it.purchaseState == Purchase.PurchaseState.PURCHASED }
                if (hasPro) {
                    prefs.edit().putBoolean("is_lifetime_pro", true).apply()
                    _isProUnlocked.value = true
                }
            }
        }
    }
}
