package br.calorscan

import android.content.Context

/** Guia para deixar as fotos com menos pos-processamento (a Samsung nao permite mudar por outro app). */
object Photos {
    const val EXPERT_RAW = "com.samsung.android.app.galaxyraw"

    data class Step(val id: String, val title: String, val where: String)

    private val NATURAL = listOf(
        Step("face", "Zerar os efeitos de rosto na selfie",
            "Câmera frontal → ícone de rosto/varinha (Efeitos de rosto) → deixe tudo em 0 ou Desligado"),
        Step("scene", "Desligar o Otimizador de cena",
            "Câmera → ⚙️ Configurações → Otimizador de cena → desligar"),
        Step("hdr", "Desligar o HDR automático (se existir)",
            "Câmera → ⚙️ Configurações → Opções avançadas de imagem → HDR automático → desligar"),
        Step("filter", "Tirar filtros e o tom de cor",
            "Câmera → ícone de filtros/estilo → Nenhum (ou Original)"),
    )
    private val DEFAULT = listOf(
        Step("face", "Voltar os efeitos de rosto",
            "Câmera frontal → ícone de rosto/varinha → Padrão (ou como você gostava)"),
        Step("scene", "Ligar o Otimizador de cena",
            "Câmera → ⚙️ Configurações → Otimizador de cena → ligar"),
        Step("hdr", "Ligar o HDR automático",
            "Câmera → ⚙️ Configurações → Opções avançadas de imagem → HDR automático → ligar"),
    )

    fun steps(natural: Boolean) = if (natural) NATURAL else DEFAULT
    fun natural(ctx: Context) = Store.getBool(ctx, "photo_natural")
    fun setNatural(ctx: Context, v: Boolean) {
        Store.putBool(ctx, "photo_natural", v)
        Store.putSet(ctx, "photo_done", emptySet()) // nova lista para o novo modo
    }
    fun done(ctx: Context) = Store.getSet(ctx, "photo_done")
    fun mark(ctx: Context, id: String, v: Boolean) {
        val s = done(ctx).toMutableSet(); if (v) s += id else s -= id
        Store.putSet(ctx, "photo_done", s)
    }
}
