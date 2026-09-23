package com.cubetimetracker

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.cubetimetracker.data.*
import com.cubetimetracker.nfc.NfcHelper
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var nfcHelper: NfcHelper
    private lateinit var db: AppDatabase

    // Stato osservabile dalla UI: nome progetto attivo, se presente
    private val activeProjectName = mutableStateOf<String?>(null)
    private val lastEventMessage = mutableStateOf("In attesa di un tag...")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        nfcHelper = NfcHelper(this)
        db = AppDatabase.getInstance(this)

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainScreen(
                        nfcAvailable = nfcHelper.isNfcAvailable(),
                        nfcEnabled = nfcHelper.isNfcEnabled(),
                        activeProjectName = activeProjectName.value,
                        statusMessage = lastEventMessage.value
                    )
                }
            }
        }

        handleIntentIfTag(intent)
    }

    override fun onResume() {
        super.onResume()
        nfcHelper.enableForegroundDispatch()
    }

    override fun onPause() {
        super.onPause()
        nfcHelper.disableForegroundDispatch()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntentIfTag(intent)
    }

    /**
     * Cuore della logica: legge l'UID del tag, lo cerca nella mappatura
     * locale, e fa toggle start/stop sul progetto corrispondente.
     * Se il tag appoggiato non è mai stato visto prima, propone
     * all'utente di associarlo a un progetto (fase di setup).
     */
    private fun handleIntentIfTag(intent: Intent) {
        val uid = nfcHelper.extractUid(intent) ?: return

        lifecycleScope.launch {
            val mapping = db.tagMappingDao().findByUid(uid)

            if (mapping == null) {
                lastEventMessage.value = "Tag sconosciuto ($uid): assegnalo a un progetto"
                // TODO: aprire TagSetupScreen precompilata con questo UID
                return@launch
            }

            val project = db.projectDao().getById(mapping.projectId)
            val openSession = db.timeSessionDao().getOpenSession()
            val now = System.currentTimeMillis()

            when {
                // Stesso tag riappoggiato mentre è già attivo -> ferma il timer
                openSession != null && openSession.projectId == mapping.projectId -> {
                    db.timeSessionDao().closeSession(openSession.id, now)
                    activeProjectName.value = null
                    lastEventMessage.value = "Timer fermato: ${project?.name}"
                }
                // Un altro progetto era attivo -> lo chiude e ne apre uno nuovo
                openSession != null -> {
                    db.timeSessionDao().closeSession(openSession.id, now)
                    db.timeSessionDao().insert(TimeSession(projectId = mapping.projectId, startEpochMillis = now))
                    activeProjectName.value = project?.name
                    lastEventMessage.value = "Passato a: ${project?.name}"
                }
                // Nessun timer attivo -> ne apre uno nuovo
                else -> {
                    db.timeSessionDao().insert(TimeSession(projectId = mapping.projectId, startEpochMillis = now))
                    activeProjectName.value = project?.name
                    lastEventMessage.value = "Timer avviato: ${project?.name}"
                }
            }
        }
    }
}

@Composable
fun MainScreen(
    nfcAvailable: Boolean,
    nfcEnabled: Boolean,
    activeProjectName: String?,
    statusMessage: String
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text("Cube Time Tracker", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))

        if (!nfcAvailable) {
            Text("Questo dispositivo non ha NFC.")
        } else if (!nfcEnabled) {
            Text("NFC disattivato: abilitalo nelle impostazioni.")
        } else {
            Text(
                if (activeProjectName != null) "In corso: $activeProjectName" else "Nessun timer attivo"
            )
            Spacer(Modifier.height(8.dp))
            Text(statusMessage, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
