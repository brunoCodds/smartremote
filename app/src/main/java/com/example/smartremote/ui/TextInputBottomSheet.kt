package com.example.smartremote.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import com.example.smartremote.R
import com.example.smartremote.databinding.BottomSheetTextInputBinding
import com.example.smartremote.manager.TvManager
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * Campo de texto livre para enviar à TV usando o mecanismo oficial
 * SendInputString (via TvManager.sendText -> TvController.sendText),
 * sem simular tecla por tecla.
 *
 * Usado tanto pelo botão "ABC" do [RemoteKeypadBottomSheet] quanto pelo
 * texto reconhecido por voz (MainActivity.startVoiceInput()), que chama
 * TvManager.sendText() diretamente sem precisar desta tela.
 *
 * Limitação conhecida do protocolo (documentada em SamsungProtocol): não
 * há como confirmar se a TV tinha um campo de texto realmente focado no
 * momento do envio - por isso o Toast de confirmação aqui é só "enviado",
 * não "recebido pela TV".
 */
class TextInputBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetTextInputBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetTextInputBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.btnSendText.setOnClickListener { sendAndDismiss() }
    }

    private fun sendAndDismiss() {
        val text = binding.editTextToSend.text?.toString().orEmpty()
        if (text.isBlank()) {
            dismiss()
            return
        }
        TvManager.sendText(text)
        Toast.makeText(requireContext(), getString(R.string.text_input_sent_toast), Toast.LENGTH_SHORT).show()
        dismiss()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "TextInputBottomSheet"
    }
}
