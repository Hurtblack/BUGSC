package com.euedrc.bugsc

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.euedrc.bugsc.databinding.FragmentQueryBinding

/** 底部栏「查询」落地页 */
class QueryFragment : Fragment() {

    private var _binding: FragmentQueryBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentQueryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.cardBlueprint.setOnClickListener {
            findNavController().navigate(R.id.BlueprintFragment)
        }
        binding.cardShipfit.setOnClickListener {
            findNavController().navigate(R.id.ShipFitFragment)
        }
        binding.cardWikelo.setOnClickListener {
            findNavController().navigate(R.id.WikeloFragment)
        }
        binding.cardMining.setOnClickListener {
            findNavController().navigate(R.id.MiningFragment)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
