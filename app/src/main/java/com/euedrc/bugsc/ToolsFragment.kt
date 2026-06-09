package com.euedrc.bugsc

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.euedrc.bugsc.databinding.FragmentToolsBinding

/** 底部栏「工具」落地页 */
class ToolsFragment : Fragment() {

    private var _binding: FragmentToolsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentToolsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.cardBug.setOnClickListener {
            findNavController().navigate(R.id.BugListFragment)
        }
        binding.cardTimer.setOnClickListener {
            findNavController().navigate(R.id.HangarTimerFragment)
        }
        binding.cardWb.setOnClickListener {
            findNavController().navigate(R.id.WbFragment)
        }
        binding.cardComing.setOnClickListener {
            Toast.makeText(requireContext(), "陆续开发中", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
