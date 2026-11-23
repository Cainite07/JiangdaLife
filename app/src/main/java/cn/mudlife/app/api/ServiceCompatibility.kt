package cn.mudlife.app.api

/** Version fields sent to upstream services. These are independent of the app release. */
internal object ServiceCompatibility {
    const val DEVICE = "6.5.24"
    const val DEVICE_SMS_LOGIN = "6.5.28"
    const val LOGISTICS = "5.0.0"
}
