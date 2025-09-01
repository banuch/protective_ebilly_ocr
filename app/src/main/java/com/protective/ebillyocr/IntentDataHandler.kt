package com.protective.ebillyocr
import android.content.Intent

class IntentDataHandler(private val intent: Intent) {

    // Extract data from intent with default values matching your JSON structure
    fun getServiceId(): String = intent.getStringExtra("service_id") ?: "123456"

    fun getValType(): String = intent.getStringExtra("valType") ?: "KWH"

    fun getValue(): String = intent.getStringExtra("value") ?: "1234.5"



    fun isEditEnabled   (): Boolean = intent.getBooleanExtra("editEnable", true)


    fun getImagePath(): String = intent.getStringExtra("image_path") ?: "/images/integrity.png"

}