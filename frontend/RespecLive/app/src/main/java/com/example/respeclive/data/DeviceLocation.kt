package com.example.respeclive.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

/**
 * 아침 브리핑 날씨용 위치. GPS를 새로 켜지 않고 기기가 마지막으로 알던 위치를 씀 (연결 지연·배터리 절약).
 * 서버는 저장하지 않고 날씨 조회에만 씀. 권한(ACCESS_COARSE_LOCATION)이 없거나 위치가 없으면 null (날씨 없이 브리핑).
 */
object DeviceLocation {
    private const val TAG = "Location"
    private const val GEOCODE_TIMEOUT_MS = 3_000L
    private const val NAME_MAX = 30            // 서버 제한

    /** name: "광진구"처럼 구 이름. 변환하지 못하면 null */
    class Place(val lat: Double, val lon: Double, val name: String?)

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    suspend fun lastKnown(context: Context): Place? = withContext(Dispatchers.IO) {
        if (!hasPermission(context)) return@withContext null
        val app = context.applicationContext
        val loc = latest(app) ?: return@withContext null
        Place(loc.latitude, loc.longitude, districtName(app, loc.latitude, loc.longitude))
    }

    /** 켜져 있는 제공자들의 마지막 위치 중 가장 최근 것 */
    @SuppressLint("MissingPermission")
    private fun latest(context: Context): Location? {
        val lm = context.getSystemService(LocationManager::class.java) ?: return null
        return lm.getProviders(true)
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
    }

    private suspend fun districtName(context: Context, lat: Double, lon: Double): String? {
        if (!Geocoder.isPresent()) return null
        val geocoder = Geocoder(context, Locale.KOREA)
        val address = withTimeoutOrNull(GEOCODE_TIMEOUT_MS) {
            try {
                geocode(geocoder, lat, lon)
            } catch (e: Exception) {
                Log.w(TAG, "구 이름을 찾지 못했어요: ${e.message}")
                null
            }
        } ?: return null
        return district(address)
    }

    private suspend fun geocode(geocoder: Geocoder, lat: Double, lon: Double): Address? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            suspendCancellableCoroutine { cont ->
                geocoder.getFromLocation(lat, lon, 1, object : Geocoder.GeocodeListener {
                    override fun onGeocode(addresses: MutableList<Address>) {
                        if (cont.isActive) cont.resume(addresses.firstOrNull())
                    }

                    override fun onError(errorMessage: String?) {
                        Log.w(TAG, "구 이름을 찾지 못했어요: $errorMessage")
                        if (cont.isActive) cont.resume(null)
                    }
                })
            }
        } else {
            @Suppress("DEPRECATION")
            geocoder.getFromLocation(lat, lon, 1)?.firstOrNull()
        }

    /** 구 이름. 구가 없는 곳은 군, 그다음 시 이름 */
    private fun district(a: Address): String? {
        val parts = listOfNotNull(a.subLocality, a.locality, a.subAdminArea) +
            (a.getAddressLine(0)?.split(" ").orEmpty())
        return listOf("구", "군", "시").firstNotNullOfOrNull { suffix ->
            parts.firstOrNull { it.length > 1 && it.endsWith(suffix) }
        }?.take(NAME_MAX)
    }
}
