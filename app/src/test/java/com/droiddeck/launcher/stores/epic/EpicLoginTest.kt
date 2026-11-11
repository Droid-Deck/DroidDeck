package com.droiddeck.launcher.stores.epic

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class EpicLoginTest {
    private fun innerText(body: String): String = JSONObject.quote(body)

    private fun corrective(action: String) =
        """{"errorCode":"errors.com.epicgames.oauth.corrective_action_required","metadata":{"correctiveAction":"$action"}}"""

    private fun epicLogin() = EpicLogin("https://www.epicgames.com/id/authorize", "test-agent", "http://127.0.0.1/launcher/authorized")

    @Test fun aRedirectPageAskingForTermsIsACorrectiveAction() {
        assertEquals("PRIVACY_POLICY_ACCEPTANCE", EpicLogin.correctiveAction(innerText(corrective("PRIVACY_POLICY_ACCEPTANCE"))))
        assertEquals("EULA_ACCEPTANCE", EpicLogin.correctiveAction(innerText(corrective("EULA_ACCEPTANCE"))))
        assertEquals("EULA_ACCEPTANCE", EpicLogin.correctiveAction(corrective("EULA_ACCEPTANCE")))
        assertEquals(EpicLogin.ACCOUNT_SIGN_IN_URL, epicLogin().browserStep(innerText(corrective("EULA_ACCEPTANCE"))))
    }

    @Test fun aCorrectiveActionWithoutItsNameStillSelectsTheBrowserStep() {
        val body = """{"errorCode":"errors.com.epicgames.oauth.corrective_action_required"}"""
        assertEquals("unknown", EpicLogin.correctiveAction(innerText(body)))
        assertEquals(EpicLogin.ACCOUNT_SIGN_IN_URL, epicLogin().browserStep(innerText(body)))
    }

    @Test fun anAuthorizationCodeOrAnotherErrorStaysInTheSignInPage() {
        val pages = listOf(
            innerText("""{"warning":"Do not share this code with any 3rd party service.","redirectUrl":"https://localhost/launcher/authorized?code=c1","authorizationCode":"c1","sid":null}"""),
            innerText("""{"errorCode":"errors.com.epicgames.common.authentication.authentication_failed","message":"Sorry"}"""),
            innerText("Sign in"),
            "null",
            "",
        )
        for (page in pages) {
            assertNull(page, EpicLogin.correctiveAction(page))
            assertNull(page, epicLogin().browserStep(page))
        }
    }

    @Test fun otherSignInFlowsHaveNoBrowserStep() {
        val url = "https://www.epicgames.com/id/authorize?client_id=c85f4c6238b04d3399bc0080e83b83fd&response_type=code&redirect_uri=http%3A%2F%2F127.0.0.1%3A41234%2Flauncher%2Fauthorized&state=x"
        val flow = EpicClient.loginFlow(url, "test-browser")!!
        assertNull(flow.browserStep(innerText(corrective("EULA_ACCEPTANCE"))))
    }
}
