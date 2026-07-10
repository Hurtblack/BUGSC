package com.euedrc.bugsc

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.euedrc.bugsc.analytics.AnalyticsTracker
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
            track("open_blueprint")
            findNavController().navigate(R.id.BlueprintFragment)
        }
        binding.cardMissionQuery.setOnClickListener {
            track("open_mission_query")
            findNavController().navigate(R.id.MissionQueryFragment)
        }
        binding.cardShipfit.setOnClickListener {
            track("open_ship_fit")
            findNavController().navigate(R.id.ShipFitFragment)
        }
        binding.cardWikelo.setOnClickListener {
            track("open_wikelo")
            findNavController().navigate(R.id.WikeloFragment)
        }
        binding.cardMarket.setOnClickListener {
            track("open_market")
            findNavController().navigate(R.id.MarketFragment)
        }
        binding.cardMining.setOnClickListener {
            track("open_mining")
            findNavController().navigate(R.id.MiningFragment)
        }
        binding.cardInventory.setOnClickListener {
            track("open_inventory")
            findNavController().navigate(R.id.InventoryFragment)
        }
    }

    private fun track(feature: String) {
        AnalyticsTracker.get(requireContext()).trackFeatureClick("query", feature)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
