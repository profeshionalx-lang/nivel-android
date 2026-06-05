package com.nivel.trainer.feature.student

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nivel.trainer.data.repository.StudentProfileRepository
import com.nivel.trainer.domain.InviteStatus
import com.nivel.trainer.domain.Problem
import com.nivel.trainer.domain.StudentInvite
import com.nivel.trainer.domain.StudentProfile
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.inject.Inject

/** Состояние инлайн-правки профиля ученика (E3): значения полей + отправка/ошибка. */
data class ProfileEditState(
    val fullName: String,
    val avatarUrl: String,
    val submitting: Boolean = false,
    val error: String? = null,
)

data class CreateSessionModalState(
    val show: Boolean = false,
    val goalId: String = "",
    val goalTitle: String = "",
    val dateInput: String = "",
    val completed: Boolean = false,
    val trainerNotes: String = "",
    val submitting: Boolean = false,
    val error: String? = null,
)

/**
 * UI-состояние экрана профиля ученика (B5) + управление (E3).
 * Без кэша: первый кадр — спиннер, дальше либо данные, либо ошибка с «Повторить».
 *
 * [goalCreator] (E2, #25) — состояние bottom-sheet создания цели.
 */
data class StudentProfileUiState(
    val loading: Boolean = true,
    val profile: StudentProfile? = null,
    val error: String? = null,
    /** E3 — инлайн-правка профиля; null = режим просмотра. */
    val editing: ProfileEditState? = null,
    /** E3 — идёт действие с приглашением (перевыпуск/отзыв). */
    val inviteBusy: Boolean = false,
    /** E3 — ошибка действия с приглашением (показываем баннером). */
    val actionError: String? = null,
    val modal: CreateSessionModalState = CreateSessionModalState(),
    val goalCreator: GoalCreatorState = GoalCreatorState(),
    val masterPlan: MasterPlanEditorState = MasterPlanEditorState(),
)

/**
 * Состояние редактора мастер-плана (E5, #28) — порт локального состояния веб-
 * `MasterPlanEditor`. Открыта максимум одна форма добавления секции и одна форма
 * добавления пункта одновременно (как `addingSectionTo`/`addingItemTo` в вебе).
 * [busy] гейтит кнопки во время сетевой операции (как `isPending`).
 */
data class MasterPlanEditorState(
    val addingSection: Boolean = false,
    val newSectionTitle: String = "",
    val newSectionCategory: String = "technique",
    val addingItemToSectionId: String? = null,
    val newItemTitle: String = "",
    val newItemDesc: String = "",
    val newItemImage: String = "",
    val busy: Boolean = false,
    val error: String? = null,
)

/**
 * Состояние создания цели (E2) — порт веб-`InlineGoalCreator`: свободный текст
 * проблемы и/или выбор из справочника. Справочник тянется лениво при первом
 * открытии шита (как `useEffect` по `open` в вебе).
 */
data class GoalCreatorState(
    val visible: Boolean = false,
    val customProblem: String = "",
    val selectedProblemId: Int? = null,
    val problems: List<Problem> = emptyList(),
    val problemsLoading: Boolean = false,
    /** Загрузка справочника сорвалась — пикер предлагает повтор (свободный текст доступен всегда). */
    val problemsFailed: Boolean = false,
    val submitting: Boolean = false,
    val error: String? = null,
) {
    /** Сохранять можно, если выбрана проблема ИЛИ введён текст (как в вебе). */
    val canSave: Boolean
        get() = !submitting && (selectedProblemId != null || customProblem.isNotBlank())
}

/**
 * ViewModel экрана профиля ученика (B5). Паттерн как у [com.nivel.trainer.feature.home.StudentsViewModel]:
 * Hilt-инъекция репозитория, единый [StateFlow], загрузка через корутину.
 *
 * Источник правды — сервер ([StudentProfileRepository.getProfile]); экран точечный,
 * Room-кэш для него не ведём. Управление учеником (E3): инлайн-правка профиля +
 * перевыпуск/отзыв приглашения (статус обновляем оптимистично).
 */
@HiltViewModel
class StudentProfileViewModel @Inject constructor(
    private val repository: StudentProfileRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(StudentProfileUiState())
    val uiState: StateFlow<StudentProfileUiState> = _uiState.asStateFlow()

    private var studentId: String? = null

    /**
     * Вызывается экраном с id из навигации. Идемпотентна: для уже принятого id
     * повторные вызовы (recompose, возврат на экран) не перезапускают загрузку.
     * Повтор после ошибки — через [refresh] (кнопка «Повторить»).
     */
    fun load(studentId: String) {
        if (this.studentId == studentId) return
        this.studentId = studentId
        refresh()
    }

    /** Тянет профиль с сервера; ошибку показываем с возможностью повтора. */
    fun refresh() {
        val id = studentId ?: return
        _uiState.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            repository.getProfile(id)
                .onSuccess { profile ->
                    _uiState.update { it.copy(loading = false, profile = profile, error = null) }
                }
                .onFailure { e ->
                    _uiState.update { it.copy(loading = false, error = mapError(e)) }
                }
        }
    }

    // --- E3: правка профиля ---

    fun startEdit() {
        val profile = _uiState.value.profile ?: return
        _uiState.update {
            it.copy(
                editing = ProfileEditState(
                    fullName = profile.fullName.orEmpty(),
                    avatarUrl = profile.avatarUrl.orEmpty(),
                ),
            )
        }
    }

    fun openModal(goalId: String, goalTitle: String) {
        _uiState.update { it.copy(modal = CreateSessionModalState(show = true, goalId = goalId, goalTitle = goalTitle)) }
    }

    fun dismissModal() {
        _uiState.update { it.copy(modal = CreateSessionModalState()) }
    }

    fun updateDateInput(v: String) {
        _uiState.update { it.copy(modal = it.modal.copy(dateInput = v, error = null)) }
    }

    fun updateCompleted(v: Boolean) {
        _uiState.update { it.copy(modal = it.modal.copy(completed = v)) }
    }

    fun updateTrainerNotes(v: String) {
        _uiState.update { it.copy(modal = it.modal.copy(trainerNotes = v)) }
    }

    fun submitCreateSession() {
        val id = studentId ?: return
        val m = _uiState.value.modal
        val isoDate = parseDate(m.dateInput)
        if (isoDate == null) {
            _uiState.update { it.copy(modal = it.modal.copy(error = "Формат даты: 2026-06-05 12:00")) }
            return
        }
        _uiState.update { it.copy(modal = it.modal.copy(submitting = true, error = null)) }
        val status = if (m.completed) "completed" else "planned"
        val completedAt = if (m.completed) isoDate else null
        viewModelScope.launch {
            repository.createSession(
                studentId = id,
                goalId = m.goalId,
                scheduledAt = isoDate,
                completedAt = completedAt,
                trainerNotes = m.trainerNotes,
                status = status,
            ).onSuccess {
                _uiState.update { it.copy(modal = CreateSessionModalState()) }
                refresh()
            }.onFailure { e ->
                _uiState.update { it.copy(modal = it.modal.copy(submitting = false, error = mapError(e))) }
            }
        }
    }

    private fun parseDate(input: String): String? = runCatching {
        val ldt = LocalDateTime.parse(input.trim(), DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
        ldt.atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
    }.getOrNull()

    // --- E2 (#25): создание цели для ученика ---

    /**
     * Открывает шит создания цели и лениво подгружает справочник проблем при
     * первом показе (как `useEffect` по `open` в вебе). Повторные открытия
     * справочник не перезапрашивают.
     */
    fun openGoalCreator() {
        _uiState.update { it.copy(goalCreator = it.goalCreator.copy(visible = true)) }
        val creator = _uiState.value.goalCreator
        if (creator.problems.isEmpty() && !creator.problemsLoading) loadProblems()
    }

    /** Закрывает шит и сбрасывает ввод (справочник кэшируем — не сбрасываем). */
    fun dismissGoalCreator() {
        _uiState.update {
            it.copy(
                goalCreator = it.goalCreator.copy(
                    visible = false,
                    customProblem = "",
                    selectedProblemId = null,
                    submitting = false,
                    error = null,
                ),
            )
        }
    }

    fun onEditNameChange(text: String) = updateEditing { it.copy(fullName = text, error = null) }

    fun onEditAvatarChange(text: String) = updateEditing { it.copy(avatarUrl = text, error = null) }

    fun cancelEdit() {
        val editing = _uiState.value.editing
        if (editing != null && editing.submitting) return // не закрываем во время сохранения
        _uiState.update { it.copy(editing = null) }
    }

    /** Сохраняет имя/аватар; пустые значения → null (как `|| null` в вебе). */
    fun saveEdit() {
        val id = studentId ?: return
        val editing = _uiState.value.editing ?: return
        if (editing.submitting) return

        _uiState.update { it.copy(editing = editing.copy(submitting = true, error = null)) }
        viewModelScope.launch {
            repository.updateProfile(
                id,
                fullName = editing.fullName.trim().ifBlank { null },
                avatarUrl = editing.avatarUrl.trim().ifBlank { null },
            )
                .onSuccess {
                    _uiState.update { it.copy(editing = null) }
                    refresh()
                }
                .onFailure { e ->
                    _uiState.update { state ->
                        val open = state.editing ?: return@update state
                        state.copy(editing = open.copy(submitting = false, error = mapError(e)))
                    }
                }
        }
    }

    fun onCustomProblemChange(text: String) {
        _uiState.update { it.copy(goalCreator = it.goalCreator.copy(customProblem = text, error = null)) }
    }

    /** Выбор проблемы из справочника; null — снять привязку. */
    fun onSelectProblem(problemId: Int?) {
        _uiState.update { it.copy(goalCreator = it.goalCreator.copy(selectedProblemId = problemId, error = null)) }
    }

    /** Повторная загрузка справочника по тапу на пикер после неудачи. */
    fun retryLoadProblems() {
        if (!_uiState.value.goalCreator.problemsLoading) loadProblems()
    }

    private fun loadProblems() {
        _uiState.update {
            it.copy(goalCreator = it.goalCreator.copy(problemsLoading = true, problemsFailed = false))
        }
        viewModelScope.launch {
            repository.getProblems()
                .onSuccess { problems ->
                    _uiState.update {
                        it.copy(
                            goalCreator = it.goalCreator.copy(
                                problems = problems,
                                problemsLoading = false,
                                problemsFailed = false,
                            ),
                        )
                    }
                }
                .onFailure {
                    // Справочник не критичен (свободный текст доступен), но даём повтор.
                    _uiState.update {
                        it.copy(goalCreator = it.goalCreator.copy(problemsLoading = false, problemsFailed = true))
                    }
                }
        }
    }

    /**
     * Создаёт цель на сервере. После успеха закрывает шит и перечитывает профиль
     * (как `router.refresh()` в вебе) — новая цель появляется в карусели.
     */
    fun submitGoal() {
        val id = studentId ?: return
        val creator = _uiState.value.goalCreator
        if (!creator.canSave) return
        _uiState.update { it.copy(goalCreator = it.goalCreator.copy(submitting = true, error = null)) }
        viewModelScope.launch {
            repository.createGoal(
                studentId = id,
                problemId = creator.selectedProblemId,
                customProblem = creator.customProblem,
            )
                .onSuccess {
                    dismissGoalCreator()
                    refresh()
                }
                .onFailure { e ->
                    _uiState.update {
                        it.copy(goalCreator = it.goalCreator.copy(submitting = false, error = mapError(e)))
                    }
                }
        }
    }

    private inline fun updateEditing(transform: (ProfileEditState) -> ProfileEditState) {
        _uiState.update { state ->
            val editing = state.editing ?: return@update state
            state.copy(editing = transform(editing))
        }
    }

    // --- E3: приглашение ---

    /** Перевыпускает приглашение; оптимистично выставляет статус pending с новой ссылкой. */
    fun regenerateInvite() {
        val id = studentId ?: return
        if (_uiState.value.inviteBusy) return

        _uiState.update { it.copy(inviteBusy = true, actionError = null) }
        viewModelScope.launch {
            repository.regenerateInvite(id)
                .onSuccess { claimUrl ->
                    _uiState.update { state ->
                        state.copy(
                            inviteBusy = false,
                            profile = state.profile?.copy(
                                invite = StudentInvite(InviteStatus.PENDING, claimUrl, null),
                            ),
                        )
                    }
                }
                .onFailure { e -> _uiState.update { it.copy(inviteBusy = false, actionError = mapError(e)) } }
        }
    }

    /** Отзывает приглашение; оптимистично выставляет статус revoked. */
    fun revokeInvite() {
        val id = studentId ?: return
        if (_uiState.value.inviteBusy) return

        _uiState.update { it.copy(inviteBusy = true, actionError = null) }
        viewModelScope.launch {
            repository.revokeInvite(id)
                .onSuccess {
                    _uiState.update { state ->
                        state.copy(
                            inviteBusy = false,
                            profile = state.profile?.copy(
                                invite = StudentInvite(InviteStatus.REVOKED, null, null),
                            ),
                        )
                    }
                }
                .onFailure { e -> _uiState.update { it.copy(inviteBusy = false, actionError = mapError(e)) } }
        }
    }

    fun dismissActionError() = _uiState.update { it.copy(actionError = null) }

    // --- E5 (#28): редактирование мастер-плана ---

    fun createMasterPlan() = mutateMasterPlan({ repository.createMasterPlan(it) })

    fun startAddSection() =
        _uiState.update { it.copy(masterPlan = it.masterPlan.copy(addingSection = true, error = null)) }

    fun cancelAddSection() =
        _uiState.update { it.copy(masterPlan = it.masterPlan.copy(addingSection = false, newSectionTitle = "")) }

    fun onNewSectionTitleChange(text: String) =
        _uiState.update { it.copy(masterPlan = it.masterPlan.copy(newSectionTitle = text)) }

    fun onNewSectionCategoryChange(category: String) =
        _uiState.update { it.copy(masterPlan = it.masterPlan.copy(newSectionCategory = category)) }

    fun submitAddSection(planId: String) {
        val mp = _uiState.value.masterPlan
        if (mp.newSectionTitle.isBlank()) return
        // sortOrder = текущее число секций (как `plan.sections.length` в вебе).
        val sortOrder = _uiState.value.profile?.masterPlan?.sections?.size ?: 0
        mutateMasterPlan(
            op = { repository.addMasterPlanSection(it, planId, mp.newSectionTitle, mp.newSectionCategory, sortOrder) },
            resetOnSuccess = { it.copy(addingSection = false, newSectionTitle = "") },
        )
    }

    fun startAddItem(sectionId: String) =
        _uiState.update { it.copy(masterPlan = it.masterPlan.copy(addingItemToSectionId = sectionId, error = null)) }

    fun cancelAddItem() =
        _uiState.update {
            it.copy(
                masterPlan = it.masterPlan.copy(
                    addingItemToSectionId = null,
                    newItemTitle = "",
                    newItemDesc = "",
                    newItemImage = "",
                ),
            )
        }

    fun onNewItemTitleChange(text: String) =
        _uiState.update { it.copy(masterPlan = it.masterPlan.copy(newItemTitle = text)) }

    fun onNewItemDescChange(text: String) =
        _uiState.update { it.copy(masterPlan = it.masterPlan.copy(newItemDesc = text)) }

    fun onNewItemImageChange(text: String) =
        _uiState.update { it.copy(masterPlan = it.masterPlan.copy(newItemImage = text)) }

    fun submitAddItem(sectionId: String) {
        val mp = _uiState.value.masterPlan
        if (mp.newItemTitle.isBlank()) return
        // sortOrder = текущее число пунктов секции (как `items.length` в вебе).
        val sortOrder = _uiState.value.profile?.masterPlan?.sections
            ?.firstOrNull { it.id == sectionId }?.items?.size ?: 0
        mutateMasterPlan(
            op = { repository.addMasterPlanItem(it, sectionId, mp.newItemTitle, mp.newItemDesc, mp.newItemImage, sortOrder) },
            resetOnSuccess = {
                it.copy(addingItemToSectionId = null, newItemTitle = "", newItemDesc = "", newItemImage = "")
            },
        )
    }

    fun deleteSection(sectionId: String) =
        mutateMasterPlan(
            op = { repository.deleteMasterPlanSection(it, sectionId) },
            // Если у удаляемой секции была открыта форма пункта — закрываем её,
            // чтобы не осталось висящего состояния на исчезнувшей секции.
            resetOnSuccess = { st ->
                if (st.addingItemToSectionId == sectionId) {
                    st.copy(addingItemToSectionId = null, newItemTitle = "", newItemDesc = "", newItemImage = "")
                } else {
                    st
                }
            },
        )

    fun deleteItem(itemId: String) =
        mutateMasterPlan({ repository.deleteMasterPlanItem(it, itemId) })

    /**
     * Общий раннер мутаций мастер-плана: гейтит повторный запуск пока [busy],
     * по успеху сбрасывает форму (resetOnSuccess) и перечитывает профиль (источник
     * правды — сервер), по ошибке показывает баннер и форму оставляет открытой.
     */
    private fun mutateMasterPlan(
        op: suspend (studentId: String) -> Result<Unit>,
        resetOnSuccess: (MasterPlanEditorState) -> MasterPlanEditorState = { it },
    ) {
        val id = studentId ?: return
        if (_uiState.value.masterPlan.busy) return
        _uiState.update { it.copy(masterPlan = it.masterPlan.copy(busy = true, error = null)) }
        viewModelScope.launch {
            op(id)
                .onSuccess {
                    _uiState.update {
                        it.copy(masterPlan = resetOnSuccess(it.masterPlan).copy(busy = false, error = null))
                    }
                    refresh()
                }
                .onFailure { e ->
                    _uiState.update { it.copy(masterPlan = it.masterPlan.copy(busy = false, error = mapError(e))) }
                }
        }
    }

    private fun mapError(e: Throwable): String =
        e.message?.takeIf { it.isNotBlank() } ?: "Что-то пошло не так. Попробуйте снова."
}
