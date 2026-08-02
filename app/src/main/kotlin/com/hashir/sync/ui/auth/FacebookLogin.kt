package com.hashir.sync.ui.auth

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.facebook.CallbackManager
import com.facebook.FacebookCallback
import com.facebook.FacebookException
import com.facebook.login.LoginBehavior
import com.facebook.login.LoginManager
import com.facebook.login.LoginResult

/**
 * Permissions requested at login.
 *
 * `pages_manage_posts` is the one that actually allows publishing; the other three are its
 * dependencies plus the Reels requirement. In Development Mode these are granted without
 * App Review to anyone holding a role on the Meta app.
 */
val FACEBOOK_PERMISSIONS: List<String> = listOf(
    "pages_show_list",
    "pages_read_engagement",
    "pages_manage_posts",
    "pages_manage_engagement",
)

/** Starts a Facebook login. [additionalAccount] selects the connect-another-account path. */
fun interface FacebookLoginLauncher {
    operator fun invoke(additionalAccount: Boolean)
}

/**
 * Wires the Facebook login SDK into Compose and hands back the short-lived token.
 *
 * The awkward part this hides is that the SDK keeps exactly one token globally, in
 * `AccessToken.getCurrentAccessToken()`. Connecting a second account therefore means:
 * take the token from the callback, persist it immediately, then clear the SDK's copy
 * before starting the next login.
 *
 * `LoginManager.logOut()` sounds more destructive than it is. It clears only the token
 * cached inside this app. It does not touch the Facebook app, the user's facebook.com
 * session, or anything on Meta's servers, and revokes nothing.
 */
@Composable
fun rememberFacebookLogin(
    onShortLivedToken: (String) -> Unit,
    onError: (String) -> Unit,
    onCancel: () -> Unit = {},
): FacebookLoginLauncher {

    val currentOnToken by rememberUpdatedState(onShortLivedToken)
    val currentOnError by rememberUpdatedState(onError)
    val currentOnCancel by rememberUpdatedState(onCancel)

    val callbackManager = remember { CallbackManager.Factory.create() }
    val loginManager = remember { LoginManager.getInstance() }

    DisposableEffect(callbackManager, loginManager) {
        loginManager.registerCallback(
            callbackManager,
            object : FacebookCallback<LoginResult> {
                override fun onSuccess(result: LoginResult) {
                    currentOnToken(result.accessToken.token)
                }

                override fun onCancel() = currentOnCancel()

                override fun onError(error: FacebookException) {
                    currentOnError(error.message ?: "Facebook login failed.")
                }
            },
        )
        onDispose { loginManager.unregisterCallback(callbackManager) }
    }

    val launcher = rememberLauncherForActivityResult(
        contract = loginManager.createLogInActivityResultContract(callbackManager, null),
    ) {
        // Deliberately empty: the registered FacebookCallback above delivers the result.
        // This lambda exists only because the contract API requires one.
    }

    return remember(launcher, loginManager) {
        FacebookLoginLauncher { additionalAccount ->
            if (additionalAccount) {
                // Clear the SDK's single cached token so the next login is not silently
                // answered with the account that is already connected.
                loginManager.logOut()

                // Force the web flow. The native path hands back whichever account is
                // signed into the installed Facebook app, which makes connecting a
                // *different* account impossible.
                //
                // Called as a method, not assigned as a property: the SDK's backing field
                // is private, so only the explicit setter is reachable from Kotlin.
                loginManager.setLoginBehavior(LoginBehavior.WEB_ONLY)
            } else {
                loginManager.setLoginBehavior(LoginBehavior.NATIVE_WITH_FALLBACK)
            }
            launcher.launch(FACEBOOK_PERMISSIONS)
        }
    }
}
