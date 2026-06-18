package com.euedrc.bugsc.agent

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
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
    private lateinit var etModel: EditText
    private lateinit var etBaseUrl: EditText
    private lateinit var rgProvider: RadioGroup
    private lateinit var rbProviderDeepSeek: RadioButton
    private lateinit var rbProviderKimi: RadioButton
    private lateinit var rbProviderMimo: RadioButton
    private lateinit var rbProviderCustom: RadioButton
    private lateinit var rbAuthBearer: RadioButton
    private lateinit var rbAuthApiKey: RadioButton
    private lateinit var tvStatus: TextView

    override fun onCreateView(inflater: LayoutInflater, parent: ViewGroup?, state: Bundle?): View =
        inflater.inflate(R.layout.fragment_agent_settings, parent, false)

    override fun onViewCreated(view: View, state: Bundle?) {
        super.onViewCreated(view, state)
        store = AgentStores.settings(requireContext())
        etKey = view.findViewById(R.id.et_agent_api_key)
        etModel = view.findViewById(R.id.et_agent_model)
        etBaseUrl = view.findViewById(R.id.et_agent_base_url)
        rgProvider = view.findViewById(R.id.rg_agent_provider)
        rbProviderDeepSeek = view.findViewById(R.id.rb_agent_provider_deepseek)
        rbProviderKimi = view.findViewById(R.id.rb_agent_provider_kimi)
        rbProviderMimo = view.findViewById(R.id.rb_agent_provider_mimo)
        rbProviderCustom = view.findViewById(R.id.rb_agent_provider_custom)
        rbAuthBearer = view.findViewById(R.id.rb_agent_auth_bearer)
        rbAuthApiKey = view.findViewById(R.id.rb_agent_auth_api_key)
        tvStatus = view.findViewById(R.id.tv_agent_setting_status)
        rgProvider.setOnCheckedChangeListener { _, _ -> applySelectedPreset() }
        view.findViewById<Button>(R.id.btn_agent_save).setOnClickListener { save() }
        view.findViewById<Button>(R.id.btn_agent_test).setOnClickListener { testConnection() }
        bind()
    }

    private fun bind() {
        val s = store.settings()
        etKey.setText(s.apiKey)
        when (s.providerId) {
            AgentSettingsStore.PROVIDER_KIMI -> rbProviderKimi.isChecked = true
            AgentSettingsStore.PROVIDER_XIAOMI_MIMO -> rbProviderMimo.isChecked = true
            AgentSettingsStore.PROVIDER_CUSTOM -> rbProviderCustom.isChecked = true
            else -> rbProviderDeepSeek.isChecked = true
        }
        etModel.setText(s.model)
        etBaseUrl.setText(s.effectiveBaseUrl)
        rbAuthApiKey.isChecked = s.authMode == AgentAuthMode.API_KEY
        rbAuthBearer.isChecked = !rbAuthApiKey.isChecked
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
                providerId = selectedProviderId(),
                model = etModel.text.toString().trim(),
                baseUrl = etBaseUrl.text.toString().trim(),
                authMode = selectedAuthMode(),
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

    private fun selectedProviderId(): String = when {
        rbProviderKimi.isChecked -> AgentSettingsStore.PROVIDER_KIMI
        rbProviderMimo.isChecked -> AgentSettingsStore.PROVIDER_XIAOMI_MIMO
        rbProviderCustom.isChecked -> AgentSettingsStore.PROVIDER_CUSTOM
        else -> AgentSettingsStore.PROVIDER_DEEPSEEK
    }

    private fun selectedAuthMode(): AgentAuthMode =
        if (rbAuthApiKey.isChecked) AgentAuthMode.API_KEY else AgentAuthMode.BEARER

    private fun applySelectedPreset() {
        val preset = AgentSettingsStore.providerPreset(selectedProviderId())
        if (selectedProviderId() != AgentSettingsStore.PROVIDER_CUSTOM) {
            etModel.setText(preset.defaultModel)
            etBaseUrl.setText(preset.defaultBaseUrl)
            rbAuthApiKey.isChecked = preset.defaultAuthMode == AgentAuthMode.API_KEY
            rbAuthBearer.isChecked = preset.defaultAuthMode == AgentAuthMode.BEARER
        }
    }
}
