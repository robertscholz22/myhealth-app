package com.myhealth.seeder

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

/** Shown by Health Connect ("Read the privacy policy") and by system settings' "See app data
 * usage" entry point. This is a throwaway test tool, so a plain explanatory string is enough. */
class PermissionsRationaleActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply {
            text = "HC Seeder is a local test tool used to write sample fitness data into " +
                "Health Connect for MyHealth development. It does not share data anywhere."
            setPadding(48, 96, 48, 48)
        })
    }
}
