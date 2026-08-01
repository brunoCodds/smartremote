package com.example.smartremote.discovery

import com.example.smartremote.model.TvDevice

/**
 * Contrato comum a todo scanner de protocolo de descoberta (SSDP, mDNS, e
 * futuramente LgDiscoveryScanner, AndroidTvDiscoveryScanner,
 * RokuDiscoveryScanner, FireTvDiscoveryScanner, VidaaDiscoveryScanner...).
 *
 * Cada implementação conhece SOMENTE o protocolo/mecanismo que implementa -
 * nenhuma conhece as outras, nem o [DiscoveryAggregator], nem a UI. Um
 * scanner só devolve TvDevice "cru" (a visão parcial da TV que aquele
 * protocolo consegue enxergar sozinho) através dos callbacks; quem decide
 * se é uma TV nova, uma atualização de uma já vista, ou duplicata é o
 * [DiscoveryAggregator] - nenhum scanner faz esse julgamento.
 *
 * Para adicionar suporte a um fabricante/protocolo novo no futuro: criar
 * uma classe que implemente esta interface e registrá-la em
 * [DeviceScanner] (que cumpre hoje o papel de orquestrador/"DiscoveryManager"
 * da arquitetura - ver o KDoc de [DeviceScanner] para a árvore completa).
 * Nenhuma outra classe desta camada precisa mudar.
 */
interface DiscoveryScanner {

    /**
     * Nome curto do scanner, usado só em diagnóstico (log e
     * [DiscoveryDiagnostics]) - ex: "SSDP", "mDNS", "Samsung". Nunca é
     * exposto na UI nem usado em nenhuma lógica de negócio.
     */
    val name: String

    /**
     * Executa a busca "do zero" na rede (multicast, query mDNS, etc) de
     * forma síncrona/bloqueante - quem chama já roda isso em background.
     * Nunca deve lançar exceção: qualquer erro vira [onError]. [onFinished]
     * é sempre chamado ao final, mesmo em caso de erro ou de [stop] ter
     * sido chamado no meio.
     *
     * Scanners de CONFIRMAÇÃO (que não descobrem "do zero", só
     * confirmam/enriquecem candidatos já vistos por outro scanner - ver
     * [SamsungDiscoveryScanner]) implementam este método como um no-op que
     * só chama [onFinished] imediatamente; o método de verdade deles tem
     * uma assinatura própria (recebe os candidatos), fora desta interface,
     * porque o contrato de entrada é diferente.
     */
    fun scan(
        onDeviceFound: (TvDevice) -> Unit,
        onFinished: () -> Unit,
        onError: (String) -> Unit
    )

    /**
     * Interrompe a busca em andamento, se houver. Seguro chamar sempre,
     * mesmo sem nenhuma busca ativa (deve ser um no-op nesse caso).
     */
    fun stop()
}
