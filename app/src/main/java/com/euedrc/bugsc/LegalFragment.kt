package com.euedrc.bugsc

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.euedrc.bugsc.databinding.FragmentLegalBinding

/** 协议与声明查看页：按导航参数加载 assets/legal 下对应 HTML */
class LegalFragment : Fragment() {

    private var _binding: FragmentLegalBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentLegalBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val doc = arguments?.getString(ARG_DOC) ?: LegalDocs.PRIVACY
        val title = arguments?.getString(ARG_TITLE) ?: "隐私政策"
        binding.tvTitle.text = title
        binding.webView.setBackgroundColor(Color.TRANSPARENT)
        binding.webView.loadUrl(LegalDocs.assetUrl(doc))
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val ARG_DOC = "doc"
        const val ARG_TITLE = "title"
    }
}
