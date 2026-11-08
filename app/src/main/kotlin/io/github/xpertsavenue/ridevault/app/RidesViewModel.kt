package io.github.xpertsavenue.ridevault.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.xpertsavenue.ridevault.model.StoredRide
import io.github.xpertsavenue.ridevault.model.Tag
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class RidesViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = (app as RideVaultApp).rides

    init {
        // Einmalig: früher importierte Mehrtages-Aufzeichnungen in Einzelfahrten teilen
        val prefs = app.getSharedPreferences("migrations", android.content.Context.MODE_PRIVATE)
        if (!prefs.getBoolean("split_multiday_v1", false)) {
            viewModelScope.launch {
                repo.splitStoredMultiDayRides()
                prefs.edit().putBoolean("split_multiday_v1", true).apply()
            }
        }
    }

    val rides: StateFlow<List<StoredRide>> =
        repo.observeRides().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Zeigt die Liste der archivierten statt der aktiven Fahrten. */
    private val _showArchive = MutableStateFlow(false)
    val showArchive: StateFlow<Boolean> = _showArchive.asStateFlow()

    fun setShowArchive(value: Boolean) {
        _showArchive.value = value
        clearSelection()
    }

    /** Die Fahrten der gerade gezeigten Liste (aktiv oder Archiv). */
    val visibleRides: StateFlow<List<StoredRide>> =
        combine(rides, _showArchive) { all, archive -> all.filter { it.archived == archive } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val archivedCount: StateFlow<Int> =
        rides.map { list -> list.count { it.archived } }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** Fahrten, deren Löschen noch bestätigt werden muss (Wischen, Auswahl). */
    private val _pendingDelete = MutableStateFlow<Set<Long>?>(null)
    val pendingDelete: StateFlow<Set<Long>?> = _pendingDelete.asStateFlow()

    fun askDelete(ids: Collection<Long>) { if (ids.isNotEmpty()) _pendingDelete.value = ids.toSet() }
    fun cancelDelete() { _pendingDelete.value = null }

    fun confirmDelete() {
        val ids = _pendingDelete.value ?: return
        _pendingDelete.value = null
        _selection.value = _selection.value - ids
        viewModelScope.launch { ids.forEach { repo.delete(it) } }
    }

    /** Meldung nach dem Archivieren/Zurückholen, mit der Möglichkeit zum Rückgängigmachen. */
    data class ArchiveEvent(val ids: Set<Long>, val archived: Boolean)

    private val _archiveEvents = MutableSharedFlow<ArchiveEvent>(extraBufferCapacity = 4)
    val archiveEvents: SharedFlow<ArchiveEvent> = _archiveEvents

    fun setArchived(ids: Collection<Long>, archived: Boolean, announce: Boolean = true) {
        if (ids.isEmpty()) return
        val set = ids.toSet()
        _selection.value = _selection.value - set
        viewModelScope.launch {
            repo.setArchived(set, archived)
            if (announce) _archiveEvents.tryEmit(ArchiveEvent(set, archived))
        }
    }

    val tags: StateFlow<List<Tag>> =
        repo.observeTags().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _selected = MutableStateFlow<StoredRide?>(null)

    /** Vollständig geladene Fahrt (mit Spur und Messpunkten) oder null. */
    val selected: StateFlow<StoredRide?> = _selected.asStateFlow()

    private val _selectedId = MutableStateFlow<Long?>(null)
    val selectedId: StateFlow<Long?> = _selectedId.asStateFlow()

    init {
        // Ändern sich Name, Tags oder Zuordnungen (auch in der Tag-Verwaltung), die geöffnete Fahrt neu laden
        viewModelScope.launch {
            rides.collect {
                val id = _selectedId.value ?: return@collect
                if (_selected.value != null) _selected.value = repo.load(id)
            }
        }
    }

    // ---- Mehrfachauswahl (Export, Karte) ----
    private val _selection = MutableStateFlow<Set<Long>>(emptySet())
    val selection: StateFlow<Set<Long>> = _selection.asStateFlow()

    fun toggleSelection(id: Long) {
        _selection.value = if (id in _selection.value) _selection.value - id else _selection.value + id
    }

    fun selectAll(ids: Collection<Long>) { _selection.value = ids.toSet() }

    /** Wählt eine ganze Gruppe: sind schon alle ihre Fahrten gewählt, werden sie abgewählt, sonst alle gewählt. */
    fun toggleGroup(ids: Collection<Long>) {
        val current = _selection.value
        _selection.value = if (ids.isNotEmpty() && current.containsAll(ids)) current - ids.toSet() else current + ids
    }

    /** Ordnet Tags allen ausgewählten Fahrten zu bzw. entfernt sie. */
    fun changeTagsOfSelection(add: Set<Long>, remove: Set<Long>) {
        val ids = _selection.value
        clearSelection()
        viewModelScope.launch { repo.changeTags(ids, add, remove) }
    }

    fun clearSelection() { _selection.value = emptySet() }

    /** Die ausgewählten Fahrten vollständig (mit Spur und Messpunkten), älteste zuerst. */
    suspend fun loadSelected(): List<StoredRide> =
        _selection.value.mapNotNull { repo.load(it) }.sortedBy { it.ride.startTime }

    private val _mapRides = MutableStateFlow<List<StoredRide>?>(null)

    /** Fahrten, die gerade gemeinsam auf einer Karte gezeigt werden (null = Karte geschlossen). */
    val mapRides: StateFlow<List<StoredRide>?> = _mapRides.asStateFlow()

    fun showSelectedOnMap() {
        viewModelScope.launch { _mapRides.value = loadSelected() }
    }

    fun closeMap() { _mapRides.value = null }

    fun open(id: Long) {
        _selectedId.value = id
        _selected.value = null
        viewModelScope.launch { _selected.value = repo.load(id) }
    }

    fun close() {
        _selectedId.value = null
        _selected.value = null
    }

    fun deleteSelected() {
        val id = _selectedId.value ?: return
        close()
        viewModelScope.launch { repo.delete(id) }
    }

    /** Löscht alle in der Liste ausgewählten Fahrten (nur in der App, nicht auf dem Fahrradcomputer). */
    fun deleteSelection() {
        val ids = _selection.value
        clearSelection()
        viewModelScope.launch { ids.forEach { repo.delete(it) } }
    }

    /** Archiviert die geöffnete Fahrt (bzw. holt sie zurück) und schließt die Ansicht. */
    fun archiveOpened(archived: Boolean) {
        val id = _selectedId.value ?: return
        close()
        setArchived(listOf(id), archived)
    }

    fun saveRideInfo(id: Long, name: String?, tagIds: Set<Long>) {
        viewModelScope.launch { repo.updateRideInfo(id, name, tagIds) }
    }

    suspend fun createTag(name: String, color: Int): Long = repo.createTag(name, color)

    suspend fun updateTag(tag: Tag): Boolean = repo.updateTag(tag)

    fun deleteTag(id: Long) {
        viewModelScope.launch { repo.deleteTag(id) }
    }
}
