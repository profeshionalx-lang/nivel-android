package com.nivel.trainer.feature.student

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nivel.trainer.data.repository.StudentProfileRepository
import com.nivel.trainer.domain.Problem
import com.nivel.trainer.domain.StudentProfile
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * UI-состояние экрана профиля ученика (B5).
 * Без кэша: первый кадр — спиннер, дальше либо данные, либо ошибка с «Повторить».
 *
 * [goalCreator] (E2, #25) — состояние bottom-sheet создания цели.
 */
data class StudentProfileUiState(
    val loading: Boolean = true,
    val profile: StudentProfile? = null,
    val error: String? = null,
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
 * Room-кэш для него не ведём.
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
     * повторные вызовы (recompose, возврат на экран) не перезапускают загрузку —
     * иначе при пересоздании composable во время первого запроса полетел бы
     * дубль. Повтор после ошибки — через [refresh] (кнопка «Повторить»).
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
