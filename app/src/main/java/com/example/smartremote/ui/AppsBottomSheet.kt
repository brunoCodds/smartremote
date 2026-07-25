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
            items = allApps,
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

        /**
         * Ordem de exibição fixa dos apps no grid. Adicionar um app novo no
         * futuro = uma linha aqui + (se já tiver App ID confiável) uma
         * entrada no APP_LAUNCH_MAP do TvController do fabricante - nada
         * mais precisa mudar nesta tela.
         */
        private val allApps: List<AppItem> = listOf(
            AppItem(RemoteKey.NETFLIX, "Netflix", "N", R.drawable.ripple_circle_netflix),
            AppItem(RemoteKey.PRIME_VIDEO, "Prime Video", "P", R.drawable.ripple_circle_prime),
            AppItem(RemoteKey.YOUTUBE, "YouTube", "Y", R.drawable.ripple_circle_youtube),
            AppItem(RemoteKey.DISNEY_PLUS, "Disney+", "D", R.drawable.ripple_circle_disney),
            AppItem(RemoteKey.MAX, "Max", "M", R.drawable.ripple_circle_max),
            AppItem(RemoteKey.GLOBOPLAY, "Globoplay", "G", R.drawable.ripple_circle_globoplay),
            AppItem(RemoteKey.APPLE_TV_PLUS, "Apple TV+", "tv", R.drawable.ripple_circle_appletv),
            AppItem(RemoteKey.PARAMOUNT_PLUS, "Paramount+", "P+", R.drawable.ripple_circle_paramount),
            AppItem(RemoteKey.CRUNCHYROLL, "Crunchyroll", "C", R.drawable.ripple_circle_crunchyroll),
            AppItem(RemoteKey.PLEX, "Plex", "Px", R.drawable.ripple_circle_plex)
        )
    }
}
