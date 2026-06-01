package com.euedrc.bugsc

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.euedrc.bugsc.data.Bug
import com.euedrc.bugsc.data.BugRepository
import com.euedrc.bugsc.data.Hardware

class BugSubmitFragment : Fragment() {

    private lateinit var repository: BugRepository
    private lateinit var etTitle: EditText
    private lateinit var etSummary: EditText
    private lateinit var etDescription: EditText
    private lateinit var etTypeTags: EditText
    private lateinit var etSteps: EditText
    private lateinit var etNotes: EditText
    private lateinit var etVram: EditText
    private lateinit var etRam: EditText
    private lateinit var spinnerSeverity: Spinner
    private lateinit var spinnerGpu: Spinner
    private lateinit var spinnerCpu: Spinner
    private lateinit var btnSubmit: Button
    private lateinit var btnSubmitIc: Button

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_bug_submit, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        repository = BugRepository(requireContext())

        etTitle = view.findViewById(R.id.et_title)
        etSummary = view.findViewById(R.id.et_summary)
        etDescription = view.findViewById(R.id.et_description)
        etTypeTags = view.findViewById(R.id.et_type_tags)
        etSteps = view.findViewById(R.id.et_steps)
        etNotes = view.findViewById(R.id.et_notes)
        etVram = view.findViewById(R.id.et_vram)
        etRam = view.findViewById(R.id.et_ram)
        spinnerSeverity = view.findViewById(R.id.spinner_severity)
        spinnerGpu = view.findViewById(R.id.spinner_gpu)
        spinnerCpu = view.findViewById(R.id.spinner_cpu)
        btnSubmit = view.findViewById(R.id.btn_submit)
        btnSubmitIc = view.findViewById(R.id.btn_submit_ic)

        setupSpinners()
        btnSubmit.setOnClickListener { onSubmit() }
        btnSubmitIc.setOnClickListener { onSubmitToIssueCouncil() }
    }

    private fun setupSpinners() {
        spinnerSeverity.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, SEVERITY_OPTIONS)
        spinnerSeverity.setSelection(1) // medium by default

        spinnerGpu.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, GPU_OPTIONS)
        spinnerCpu.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, CPU_OPTIONS)
    }

    private fun onSubmit() {
        val input = buildInputBug() ?: return
        try {
            val bug = repository.createLocalBug(input)

            Toast.makeText(requireContext(), "提交成功", Toast.LENGTH_SHORT).show()
            findNavController().navigate(R.id.action_BugSubmitFragment_to_BugDetailFragment, Bundle().apply {
                putString("bugId", bug.id)
            })
        } catch (e: Exception) {
            Toast.makeText(requireContext(), e.message ?: "提交失败", Toast.LENGTH_SHORT).show()
        }
    }

    private fun onSubmitToIssueCouncil() {
        val input = buildInputBug() ?: return
        val text = BugIssueCouncilFormatter.format(input)
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Issue Council report", text))
        Toast.makeText(requireContext(), "已复制 IC 反馈模板，正在应用内打开 Issue Council", Toast.LENGTH_LONG).show()
        findNavController().navigate(R.id.action_BugSubmitFragment_to_IssueCouncilFragment)
    }

    private fun buildInputBug(): Bug? {
        val title = etTitle.text.toString().trim()
        val summary = etSummary.text.toString().trim()
        val description = etDescription.text.toString().trim()
        val notes = etNotes.text.toString().trim()

        val steps = etSteps.text.toString()
            .split("\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val typeTags = etTypeTags.text.toString()
            .split(Regex("[,，\\s]+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        if (title.isEmpty() || summary.isEmpty() || steps.isEmpty()) {
            Toast.makeText(requireContext(), "标题、摘要、步骤至少一条为必填", Toast.LENGTH_SHORT).show()
            return null
        }

        return Bug(
            id = "",
            title = title,
            summary = summary,
            description = description,
            notes = notes,
            steps = steps,
            typeTags = typeTags,
            severity = SEVERITY_OPTIONS[spinnerSeverity.selectedItemPosition],
            hardware = Hardware(
                gpuVendors = listOf(GPU_OPTIONS[spinnerGpu.selectedItemPosition]),
                vramMinGB = etVram.text.toString().toIntOrNull() ?: 0,
                ramMinGB = etRam.text.toString().toIntOrNull() ?: 0,
                cpuVendors = listOf(CPU_OPTIONS[spinnerCpu.selectedItemPosition])
            )
        )
    }

    companion object {
        val GPU_OPTIONS = arrayOf("Any", "NVIDIA", "AMD", "Intel")
        val CPU_OPTIONS = arrayOf("Any", "Intel", "AMD")
        val SEVERITY_OPTIONS = arrayOf("low", "medium", "high")
    }
}
