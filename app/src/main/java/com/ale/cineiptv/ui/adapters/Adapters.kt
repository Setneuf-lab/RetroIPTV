package com.ale.cineiptv.ui.adapters

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import coil.dispose
import coil.load
import com.ale.cineiptv.R
import com.ale.cineiptv.data.HistoryEntry
import com.ale.cineiptv.databinding.ItemChannelRowBinding
import com.ale.cineiptv.databinding.ItemContinueBinding
import com.ale.cineiptv.databinding.ItemGroupBinding
import com.ale.cineiptv.databinding.ItemPosterBinding
import com.ale.cineiptv.util.ChannelName
import com.ale.cineiptv.util.addFocusScale

/** Datos mínimos para pintar una fila o una carátula. */
data class DisplayItem(
    val title: String,
    val subtitle: String?,
    val logo: String?,
    val number: Int?,
    val favorite: Boolean = false,
    /** Etiqueta de calidad ya calculada (canales agrupados); null = se saca del nombre. */
    val badge: String? = null,
)

private fun ImageView.loadLogo(url: String?) {
    if (url.isNullOrBlank()) {
        dispose()
        setImageResource(R.drawable.ic_placeholder)
    } else {
        load(url) {
            crossfade(false)
            placeholder(R.drawable.ic_placeholder)
            error(R.drawable.ic_placeholder)
        }
    }
}

/** Canales / películas / series / episodios. Dos vistas: fila (lista) o carátula (cuadrícula). */
class ItemAdapter(
    private val onClick: (Int) -> Unit,
    private val onLongClick: ((Int) -> Unit)? = null,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var items: List<DisplayItem> = emptyList()
    private var posterMode = false
    /** En móvil no se muestra el grupo a la derecha (más espacio para el nombre). */
    private var showGroup: Boolean? = null

    /** Actualiza un único elemento (p. ej. al marcar favorito) sin redibujar la lista. */
    fun updateItem(position: Int, item: DisplayItem) {
        if (position !in items.indices) return
        items = items.toMutableList().also { it[position] = item }
        notifyItemChanged(position)
    }

    fun itemAt(position: Int): DisplayItem? = items.getOrNull(position)

    // Con listas de >100.000 elementos DiffUtil sería lento; un refresco completo es instantáneo.
    @SuppressLint("NotifyDataSetChanged")
    fun submit(list: List<DisplayItem>, poster: Boolean) {
        items = list
        posterMode = poster
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size
    override fun getItemViewType(position: Int) = if (posterMode) TYPE_POSTER else TYPE_ROW

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        if (showGroup == null) showGroup = parent.context.resources.getBoolean(R.bool.show_row_group)
        return if (viewType == TYPE_POSTER) {
            PosterVH(ItemPosterBinding.inflate(inflater, parent, false))
        } else {
            RowVH(ItemChannelRowBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = items[position]
        when (holder) {
            is RowVH -> holder.bind(item)
            is PosterVH -> holder.bind(item)
        }
    }

    private fun RecyclerView.ViewHolder.attachClicks() {
        itemView.setOnClickListener {
            val pos = bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) onClick(pos)
        }
        // Pulsación larga (mantener OK en el mando) = añadir/quitar de favoritos
        itemView.setOnLongClickListener {
            val pos = bindingAdapterPosition
            val handler = onLongClick
            if (pos != RecyclerView.NO_POSITION && handler != null) { handler(pos); true } else false
        }
    }

    inner class RowVH(private val vb: ItemChannelRowBinding) : RecyclerView.ViewHolder(vb.root) {
        init { attachClicks() }

        fun bind(item: DisplayItem) {
            vb.textNumber.isVisible = item.number != null
            vb.textNumber.text = item.number?.toString().orEmpty()
            val (name, quality) = if (item.badge != null) item.title to item.badge else ChannelName.split(item.title)
            vb.textName.text = if (item.favorite) "★  $name" else name
            vb.textQuality.isVisible = quality != null
            vb.textQuality.text = quality.orEmpty()
            vb.textGroup.isVisible = showGroup == true && !item.subtitle.isNullOrBlank()
            vb.textGroup.text = item.subtitle.orEmpty()
            vb.imageLogo.loadLogo(item.logo)
        }
    }

    inner class PosterVH(private val vb: ItemPosterBinding) : RecyclerView.ViewHolder(vb.root) {
        init {
            vb.root.addFocusScale(1.07f)
            attachClicks()
        }

        fun bind(item: DisplayItem) {
            val (name, quality) = ChannelName.split(item.title)
            vb.textTitle.text = name
            vb.textQuality.isVisible = quality != null
            vb.textQuality.text = quality.orEmpty()
            vb.textFav.isVisible = item.favorite
            vb.textSubtitle.isVisible = !item.subtitle.isNullOrBlank()
            vb.textSubtitle.text = item.subtitle.orEmpty()
            vb.imagePoster.loadLogo(item.logo)
        }
    }

    private companion object {
        const val TYPE_ROW = 0
        const val TYPE_POSTER = 1
    }
}

/** Lista de grupos (vertical en TV/horizontal en móvil vertical). */
class GroupAdapter(
    private val groups: List<String>,
    private val horizontal: Boolean,
    private val isLocked: (String) -> Boolean,
    private val onClick: (Int) -> Unit,
    private val onLongClick: (Int) -> Unit,
) : RecyclerView.Adapter<GroupAdapter.VH>() {

    private var selected = 0

    fun setSelected(index: Int) {
        val old = selected
        selected = index
        notifyItemChanged(old)
        notifyItemChanged(index)
    }

    override fun getItemCount() = groups.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val vb = ItemGroupBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        if (horizontal) {
            vb.root.layoutParams = vb.root.layoutParams.apply { width = ViewGroup.LayoutParams.WRAP_CONTENT }
        }
        return VH(vb)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val name = groups[position]
        holder.vb.root.text = if (position > 0 && isLocked(name)) "🔒  $name" else name
        holder.vb.root.isSelected = position == selected
    }

    @SuppressLint("NotifyDataSetChanged")
    fun refreshAll() = notifyDataSetChanged()

    inner class VH(val vb: ItemGroupBinding) : RecyclerView.ViewHolder(vb.root) {
        init {
            vb.root.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onClick(pos)
            }
            // Mantener OK sobre un grupo = bloquearlo/desbloquearlo (control parental)
            vb.root.setOnLongClickListener {
                val pos = bindingAdapterPosition
                if (pos > 0) { onLongClick(pos); true } else false
            }
        }
    }
}

/** Fila horizontal de "Seguir viendo" en el menú principal. */
class ContinueAdapter(
    private val onClick: (HistoryEntry) -> Unit,
    private val onLongClick: (HistoryEntry) -> Unit,
) : RecyclerView.Adapter<ContinueAdapter.VH>() {

    private var items: List<HistoryEntry> = emptyList()

    @SuppressLint("NotifyDataSetChanged")
    fun submit(list: List<HistoryEntry>) {
        items = list
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemContinueBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

    inner class VH(private val vb: ItemContinueBinding) : RecyclerView.ViewHolder(vb.root) {
        init {
            vb.root.addFocusScale(1.06f)
            vb.root.setOnClickListener { items.getOrNull(bindingAdapterPosition)?.let(onClick) }
            vb.root.setOnLongClickListener {
                items.getOrNull(bindingAdapterPosition)?.let { onLongClick(it); true } ?: false
            }
        }

        fun bind(e: HistoryEntry) {
            val ctx = vb.root.context
            // Episodios: "Serie · S01E03 …"
            vb.textTitle.text = e.show?.let { "${it.name} · ${e.channel.name.removePrefix(it.name).trim(' ', '-', '·', ':')}" }
                ?: e.channel.name
            vb.imageCover.loadLogo(e.channel.logo ?: e.show?.logo)
            vb.progressWatched.progress = (e.progress * 1000).toInt()
            val min = (e.remainingMs / 60_000).toInt().coerceAtLeast(1)
            vb.textRemaining.text = if (min >= 60) ctx.getString(R.string.continue_remaining_h, min / 60, min % 60)
            else ctx.getString(R.string.continue_remaining_min, min)
        }
    }
}
