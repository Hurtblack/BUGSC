package com.euedrc.bugsc.agent

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.euedrc.bugsc.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AgentSettingsFragment : Fragment() {

    private lateinit var store: AgentSettingsStore
    private lateinit var etKey: EditText
    private lateinit var rbFlash: RadioButton
    private lateinit var rbPro: RadioButton
    private lateinit var tvStatus: TextView

    override fun onCreateView(inflater: LayoutInflater, parent: ViewGroup?, state: Bundle?): View =
        inflater.inflate(R.layout.fragment_agent_settings, parent, false)

    override fun onViewCreated(view: View, state: Bundle?) {
        super.onViewCreated(view, state)
        store = AgentStores.settings(requireContext())
        etKey = view.findViewById(R.id.et_agent_api_key)
        rbFlash = view.findViewById(R.id.rb_agent_model_flash)
        rbPro = view.findViewById(R.id.rb_agent_model_pro)
        tvStatus = view.findViewById(R.id.tv_agent_setting_status)
        view.findViewById<Button>(R.id.btn_agent_save).setOnClickListener { save() }
        view.findViewById<Button>(R.id.btn_agent_test).setOnClickListener { testConnection() }
        bind()
    }

    private fun bind() {
        val s = store.settings()
        etKey.setText(s.apiKey)
        rbPro.isChecked = s.model == AgentSettingsStore.MODEL_DEEPSEEK_PRO
        rbFlash.isChecked = !rbPro.isChecked
        tvStatus.text = when (s.lastTestStatus) {
            AgentConnectionStatus.SUCCESS -> getString(R.string.agent_test_success)
            AgentConnectionStatus.FAILURE -> getString(R.string.agent_test_failure)
            AgentConnectionStatus.NOT_TESTED -> getString(R.string.agent_test_not_tested)
        }
    }

    private fun save(status: AgentConnectionStatus? = null) {
        val current = store.settings()
        store.save(
            AgentSettings(
                apiKey = etKey.text.toString().trim(),
                model = if (rbPro.isChecked) AgentSettingsStore.MODEL_DEEPSEEK_PRO else AgentSettingsStore.MODEL_DEEPSEEK_FLASH,
                lastTestAt = if (status == null) current.lastTestAt else System.currentTimeMillis(),
                lastTestStatus = status ?: current.lastTestStatus,
            ),
        )
        Toast.makeText(requireContext(), R.string.agent_settings_saved, Toast.LENGTH_SHORT).show()
        bind()
    }

    private fun testConnection() {
        save(AgentConnectionStatus.NOT_TESTED)
        tvStatus.text = getString(R.string.agent_test_running)
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                DeepSeekClient(UrlConnectionDeepSeekTransport()).testConnection(store.settings())
            }
            save(if (ok) AgentConnectionStatus.SUCCESS else AgentConnectionStatus.FAILURE)
        }
    }
}
