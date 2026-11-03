package com.lsa.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lsa.app.data.prefs.SettingsStore
import com.lsa.app.ml.HostState
import com.lsa.app.ml.ModelHost
import io.github.taehunkim.ondolsaem.sdk.OndolSaem.Categories
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Runs against the real OndolSaem app when it is installed on the device (skipped otherwise): connection, mirroring
 * of its active models into the settings, and a real embedding when its search model is downloaded.
 */
@RunWith(AndroidJUnit4::class)
class ModelHostTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val settings = SettingsStore(ctx)
    private lateinit var host: ModelHost

    @Before fun setUp() {
        host = ModelHost(ctx, settings, scope)
        assumeTrue("OndolSaem is not installed", host.client.isInstalled())
    }

    @After fun tearDown() { host.client.disconnect(); scope.cancel() }

    @Test fun refreshConnectsAndMirrorsTheActiveModels() = runBlocking {
        val s = host.refresh()
        assertEquals(HostState.Status.CONNECTED, s.status)
        assertTrue("host version known", !s.hostVersion.isNullOrEmpty())
        assertTrue(s.hostApi >= io.github.taehunkim.ondolsaem.sdk.OndolSaem.Api.V1)
        val emb = s.models[Categories.EMBEDDING]
        assertNotNull(emb)
        assertEquals(if (emb!!.ready) emb.id else "", settings.embeddingModelId.first())
        val asr = s.models.getValue(Categories.ASR)
        assertEquals(if (asr.ready) asr.id else "none", settings.asrModelId.first())
    }

    @Test fun aHostBelowTheNeededLevelIsReportedAsTooOld() = runBlocking {
        val sdk = io.github.taehunkim.ondolsaem.sdk.OndolSaem
        val demanding = ModelHost(ctx, settings, scope, io.github.taehunkim.ondolsaem.sdk.OndolSaemClient(ctx, minHostApi = sdk.API_VERSION + 1))
        try {
            assertEquals(HostState.Status.HOST_TOO_OLD, demanding.refresh().status)
        } finally { demanding.client.disconnect() }
    }

    @Test fun embedsThroughTheHost() = runBlocking {
        assumeTrue("no embedding model downloaded in OndolSaem", host.refresh().ready(Categories.EMBEDDING))
        host.embedder()!!.use { lease ->
            val e = lease.value
            assertEquals(ModelHost.BACKEND, e.backend)
            val cat = e.embedQuery("a cat sleeping on a sofa")
            val norm = sqrt(cat.sumOf { (it * it).toDouble() })
            assertTrue("normalized vector, got norm $norm", abs(norm - 1) < 1e-3)
            val kitten = e.embedDocument("A kitten is napping on the couch", "pets")
            val tax = e.embedDocument("Quarterly corporate tax filing deadlines", "finance")
            fun dot(a: FloatArray, b: FloatArray) = a.indices.sumOf { (a[it] * b[it]).toDouble() }
            assertTrue("related text scores higher", dot(cat, kitten) > dot(cat, tax))
        }
    }

    @Test fun speechWorksWithoutTranscriptionModelToo() = runBlocking {
        assumeTrue(host.refresh().status == HostState.Status.CONNECTED)
        host.speech().use { lease ->
            val pcm = java.io.File.createTempFile("silence", ".pcm", ctx.cacheDir)
            try {
                pcm.writeBytes(ByteArray(32_000 * 2)) // 2 s of silence
                assertEquals(emptyList<Any>(), lease.value.speechSpans(pcm))
            } finally { pcm.delete() }
        }
    }
}
