package com.example.smartremote.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.example.smartremote.databinding.BottomSheetKeypadBinding
import com.example.smartremote.manager.TvManager
import com.example.smartremote.model.RemoteKey
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * Teclado numérico + teclas coloridas do controle remoto, no mesmo padrão
 * visual do controle oficial da Samsung. Todos os botões chamam
 * TvManager.sendRemoteKey() diretamente - nenhum conhecimento de Samsung
 * aqui, só RemoteKey genérico.
 *
 * O botão "ABC" alterna para o modo de digitação de texto livre
 * ([TextInputBottomSheet]), fechando este BottomSheet e abrindo o outro -
 * mesmo comportamento do controle oficial, que também alterna entre os
 * dois modos em vez de mostrar os dois ao mesmo tempo.
 */
class RemoteKeypadBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetKeypadBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetKeypadBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnNum0.setOnClickListener { send(RemoteKey.NUM_0) }
        binding.btnNum1.setOnClickListener { send(RemoteKey.NUM_1) }
        binding.btnNum2.setOnClickListener { send(RemoteKey.NUM_2) }
        binding.btnNum3.setOnClickListener { send(RemoteKey.NUM_3) }
        binding.btnNum4.setOnClickListener { send(RemoteKey.NUM_4) }
        binding.btnNum5.setOnClickListener { send(RemoteKey.NUM_5) }
        binding.btnNum6.setOnClickListener { send(RemoteKey.NUM_6) }
        binding.btnNum7.setOnClickListener { send(RemoteKey.NUM_7) }
        binding.btnNum8.setOnClickListener { send(RemoteKey.NUM_8) }
        binding.btnNum9.setOnClickListener { send(RemoteKey.NUM_9) }

        binding.btnColorRed.setOnClickListener { send(RemoteKey.RED) }
        binding.btnColorGreen.setOnClickListener { send(RemoteKey.GREEN) }
        binding.btnColorYellow.setOnClickListener { send(RemoteKey.YELLOW) }
        binding.btnColorBlue.setOnClickListener { send(RemoteKey.BLUE) }

        binding.btnKeypadTextMode.setOnClickListener {
            dismiss()
            TextInputBottomSheet().show(parentFragmentManager, TextInputBottomSheet.TAG)
        }
    }

    private fun send(key: RemoteKey) {
        TvManager.sendRemoteKey(key)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "RemoteKeypadBottomSheet"
    }
}
