package com.schindler.k2m

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LaunchSmokeTest {
    // The app asks for notification permission at launch; the system dialog would pause the activity.
    @get:Rule val perms: GrantPermissionRule = GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)

    @Test fun mainActivityLaunchesAndResumes() {
        ActivityScenario.launch(MainActivity::class.java).close()
    }
}
