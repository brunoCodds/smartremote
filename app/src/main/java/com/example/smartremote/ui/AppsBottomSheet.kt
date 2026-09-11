package com.example.smartremote.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.recyclerview.widget.GridLayoutManager
import com.example.smartremote.R
import com.example.smartremote.databinding.BottomSheetAppsBinding
import com.example.smartremote.manager.TvManager
import com.example.smartremote.model.RemoteKey
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * Grade de apps disponíveis para abrir na TV. Aberto pelo botão "Apps"
 * (btnApps) da tela principal, no mesmo padrão de
 * RemoteKeypadBottomSheet/TextInputBottomSheet.
 *
 * A LISTA de apps aqui é fixa e independente de fabricante - é só a UI.
 * Quem decide se um app específico funciona na TV conectada é
 * TvManager.getSupportedApps() (que delega ao TvController ativo - ver
 * TvController.supportedApps()). Um app fora desse conjunto ainda aparece
 * no grid (com alpha reduzido, via AppsAdapter), só não dispara o comando
 * de verdade - mostra um aviso em vez disso. Isso já deixa a tela pronta
 * para quando existirem controllers de outros fabricantes: cada um vai
 * simplesmente suportar um subconjunto diferente destes mesmos apps, sem
 * precisar mudar nada aqui.
 */
class AppsBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetAppsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetAppsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val supportedApps = TvManager.getSupportedApps()
        binding.recyclerApps.layoutManager = GridLayoutManager(requireContext(), GRID_SPAN_COUNT)
        binding.recyclerApps.adapter = AppsAdapter(
            items = AppCatalog.apps,
            supportedApps = supportedApps,
            onAppClick = { item -> onAppTapped(item, supportedApps) }
        )
    }

    private fun onAppTapped(item: AppItem, supportedApps: Set<RemoteKey>) {
        if (!supportedApps.contains(item.key)) {
            Toast.makeText(requireContext(), getString(R.string.app_not_supported_toast), Toast.LENGTH_SHORT).show()
            return
        }
        TvManager.sendRemoteKey(item.key)
        dismiss()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "AppsBottomSheet"
        private const val GRID_SPAN_COUNT = 4
    }
}
