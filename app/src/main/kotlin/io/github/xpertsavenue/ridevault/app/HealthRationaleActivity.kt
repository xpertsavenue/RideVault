package io.github.xpertsavenue.ridevault.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/** Erklärt, wofür die App Health Connect nutzt (Health Connect zeigt diese Seite beim Freigeben der Berechtigungen). */
class HealthRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = (application as RideVaultApp).settings
        setContent {
            val settings by store.state.collectAsState()
            RideVaultTheme(settings) {
                Surface {
                    Column(Modifier.padding(24.dp)) {
                        Text(stringResource(R.string.health_rationale_title), style = MaterialTheme.typography.titleLarge)
                        Text(stringResource(R.string.health_rationale_text), modifier = Modifier.padding(top = 12.dp))
                    }
                }
            }
        }
    }
}
