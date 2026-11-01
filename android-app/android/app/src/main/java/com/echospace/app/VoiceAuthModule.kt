package com.echospace.app

import com.facebook.react.ReactPackage
import com.facebook.react.bridge.NativeModule
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.uimanager.ViewManager
import com.google.firebase.auth.FirebaseAuth

class VoiceAuthModule(context: ReactApplicationContext) : ReactContextBaseJavaModule(context) {
  override fun getName() = "VoiceAuth"

  @ReactMethod
  fun startupContext(promise: Promise) { VoiceStartup.get(reactApplicationContext, promise) }

  @ReactMethod
  fun signIn(email: String, password: String, promise: Promise) {
    FirebaseAuth.getInstance().signInWithEmailAndPassword(email.trim(), password)
      .addOnSuccessListener { promise.resolve(it.user?.email) }
      .addOnFailureListener { promise.reject("SIGN_IN_FAILED", "Unable to sign in. Check your details and connection.") }
  }

  @ReactMethod
  fun currentUser(promise: Promise) { promise.resolve(FirebaseAuth.getInstance().currentUser?.email) }

  @ReactMethod
  fun idToken(promise: Promise) {
    val user = FirebaseAuth.getInstance().currentUser
    if (user == null) { promise.reject("NOT_SIGNED_IN", "Sign in first."); return }
    user.getIdToken(false)
      .addOnSuccessListener { result ->
        if (result.token == null) promise.reject("NO_TOKEN", "Could not obtain a sign-in token.")
        else promise.resolve(result.token)
      }
      .addOnFailureListener { promise.reject("TOKEN_FAILED", "Could not refresh your sign-in.") }
  }

  @ReactMethod
  fun signOut(promise: Promise) { FirebaseAuth.getInstance().signOut(); promise.resolve(null) }
}

class VoiceAuthPackage : ReactPackage {
  override fun createNativeModules(context: ReactApplicationContext): List<NativeModule> = listOf(VoiceAuthModule(context))
  override fun createViewManagers(context: ReactApplicationContext): List<ViewManager<*, *>> = emptyList()
}
