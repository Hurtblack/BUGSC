package com.euedrc.bugsc

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.euedrc.bugsc.data.Bug
import com.euedrc.bugsc.data.BugRepository
import com.google.android.material.chip.Chip
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BugDetailFragment : Fragment() {

    private lateinit var repository: BugRepository
    private var bugId: String = ""
    private var bug: Bug? = null

    private lateinit var tvNotFound: TextView
    private lateinit var detailContainer: LinearLayout
    private lateinit var tvTitle: TextView
    private lateinit var tvSeverity: TextView
    private lateinit var tvSummary: TextView
    private lateinit var tvDescription: TextView
    private lateinit var tvGpu: TextView
    private lateinit var tvVram: TextView
    private lateinit var tvRam: TextView
    private lateinit var tvCpu: TextView
    private lateinit var tvNotesLabel: TextView
    private lateinit var tvNotes: TextView
    private lateinit var tvUpdated: TextView
    private lateinit var containerTypeTags: LinearLayout
    private lateinit var containerSteps: LinearLayout
    private lateinit var btnCopy: Button
    private lateinit var btnShare: Button
    private lateinit var btnFav: Button

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_bug_detail, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        repository = BugRepository(requireContext())
        bugId = arguments?.getString("bugId") ?: ""

        tvNotFound = view.findViewById(R.id.tv_not_found)
        detailContainer = view.findViewById(R.id.detail_container)
        tvTitle = view.findViewById(R.id.tv_title)
        tvSeverity = view.findViewById(R.id.tv_severity)
        tvSummary = view.findViewById(R.id.tv_summary)
        tvDescription = view.findViewById(R.id.tv_description)
        tvGpu = view.findViewById(R.id.tv_gpu)
        tvVram = view.findViewById(R.id.tv_vram)
        tvRam = view.findViewById(R.id.tv_ram)
        tvCpu = view.findViewById(R.id.tv_cpu)
        tvNotesLabel = view.findViewById(R.id.tv_notes_label)
        tvNotes = view.findViewById(R.id.tv_notes)
        tvUpdated = view.findViewById(R.id.tv_updated)
        containerTypeTags = view.findViewById(R.id.container_type_tags)
        containerSteps = view.findViewById(R.id.container_steps)
        btnCopy = view.findViewById(R.id.btn_copy)
        btnShare = view.findViewById(R.id.btn_share)
        btnFav = view.findViewById(R.id.btn_fav)

        loadDetail()
        setupButtons()
    }

    override fun onResume() {
        super.onResume()
        if (bugId.isNotEmpty()) loadDetail()
    }

    private fun loadDetail() {
        bug = repository.getBugById(bugId)
        if (bug == null) {
            tvNotFound.visibility = View.VISIBLE
            detailContainer.visibility = View.GONE
            return
        }

        val b = bug!!
        tvNotFound.visibility = View.GONE
        detailContainer.visibility = View.VISIBLE

        tvTitle.text = b.title
        tvSummary.text = b.summary
        tvDescription.text = b.description

        // Severity
        if (b.severity.isNotEmpty()) {
            tvSeverity.visibility = View.VISIBLE
            tvSeverity.text = b.severity
            val bg = when (b.severity) {
                "high" -> "#ff4565"
                "medium" -> "#ff8a3d"
                "low" -> "#21d4ff"
                else -> "#4a6377"
            }
            tvSeverity.setBackgroundColor(Color.parseColor(bg))
        } else {
            tvSeverity.visibility = View.GONE
        }

        // Hardware
        val hw = b.hardware
        tvGpu.text = "GPU：${normalizeVendors(hw.gpuVendors)}"
        tvVram.text = "显存最低：${hw.vramMinGB} GB"
        tvRam.text = "内存最低：${hw.ramMinGB} GB"
        tvCpu.text = "CPU：${normalizeVendors(hw.cpuVendors)}"

        // Type tags
        containerTypeTags.removeAllViews()
        b.typeTags.forEach { tag ->
            val chip = Chip(requireContext()).apply {
                text = tag
                isCheckable = false
                chipBackgroundColor = android.content.res.ColorStateList.valueOf(Color.parseColor("#112433"))
                setTextColor(Color.parseColor("#21d4ff"))
                setTextSize(11f)
                setEnsureMinTouchTargetSize(false)
                chipMinHeight = 0f
            }
            containerTypeTags.addView(chip)
        }

        // Steps
        containerSteps.removeAllViews()
        b.steps.forEachIndexed { index, step ->
            val tv = TextView(requireContext()).apply {
                text = "${index + 1}. $step"
                textSize = 13f
                setTextColor(Color.parseColor("#7c95a8"))
                setLineSpacing(2f, 1f)
            }
            containerSteps.addView(tv)
        }

        // Notes
        if (b.notes.isNotEmpty()) {
            tvNotesLabel.visibility = View.VISIBLE
            tvNotes.visibility = View.VISIBLE
            tvNotes.text = b.notes
        } else {
            tvNotesLabel.visibility = View.GONE
            tvNotes.visibility = View.GONE
        }

        tvUpdated.text = "更新时间：${formatDateTime(b.updatedAt)}"

        // Favorite button
        updateFavButton()
    }

    private fun setupButtons() {
        btnCopy.setOnClickListener {
            val b = bug ?: return@setOnClickListener
            val lines = mutableListOf<String>()
            lines.add("标题：${b.title}")
            lines.add("解决步骤：")
            b.steps.forEachIndexed { i, s -> lines.add("${i + 1}. $s") }
            if (b.notes.isNotEmpty()) lines.add("注意事项：${b.notes}")

            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("solution", lines.joinToString("\n")))
            Toast.makeText(requireContext(), "已复制解决方案", Toast.LENGTH_SHORT).show()
        }

        btnShare.setOnClickListener {
            val b = bug ?: return@setOnClickListener
            val code = repository.exportBugShareCode(b.id)
            if (code == null) {
                Toast.makeText(requireContext(), "分享码生成失败", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("shareCode", code))
            Toast.makeText(requireContext(), "分享码已复制", Toast.LENGTH_SHORT).show()
        }

        btnFav.setOnClickListener {
            val b = bug ?: return@setOnClickListener
            val isFav = repository.toggleFavorite(b.id)
            bug = repository.getBugById(b.id)
            updateFavButton()
            Toast.makeText(requireContext(), if (isFav) "已收藏" else "已取消收藏", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateFavButton() {
        val b = bug ?: return
        btnFav.text = if (repository.isFavorite(b.id)) "取消收藏" else "收藏"
    }

    private fun normalizeVendors(vendors: List<String>): String {
        if (vendors.isEmpty() || vendors.contains("Any")) return "不限"
        return vendors.joinToString("/")
    }

    private fun formatDateTime(timestamp: Long): String {
        if (timestamp <= 0) return "-"
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }
}
