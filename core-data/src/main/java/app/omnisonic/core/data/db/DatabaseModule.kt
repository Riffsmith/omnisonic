package app.omnisonic.core.data.db

import android.content.Context
import app.omnisonic.core.data.json.OmnisonicJson
import app.omnisonic.core.data.security.AesGcmCredentialCodec
import app.omnisonic.core.data.security.AndroidKeystoreKeySource
import app.omnisonic.core.data.security.CredentialCodec
import com.squareup.moshi.Moshi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun omnisonicDatabase(@ApplicationContext context: Context): OmnisonicDatabase =
        OmnisonicDatabaseFactory(context).open()

    @Provides
    fun moshi(): Moshi = OmnisonicJson.moshi

    @Provides
    fun credentialCodec(): CredentialCodec =
        AesGcmCredentialCodec(keySource = AndroidKeystoreKeySource())
}
