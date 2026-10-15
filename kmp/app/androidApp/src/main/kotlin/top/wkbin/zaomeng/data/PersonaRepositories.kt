package top.wkbin.zaomeng.data

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import top.wkbin.zaomeng.platform.ClientDiagnostics
import top.wkbin.zaomeng.data.api.DeleteStatusDto
import top.wkbin.zaomeng.data.local.OriginalKnowledgeClient
import top.wkbin.zaomeng.data.local.PersonaClient
import top.wkbin.zaomeng.data.api.OriginalKnowledgeEntryDto
import top.wkbin.zaomeng.data.api.PersonaAvatarDto
import top.wkbin.zaomeng.data.api.PersonaQualityOverviewDto
import top.wkbin.zaomeng.data.api.PersonaQualityReportDto
import top.wkbin.zaomeng.data.api.PersonaRepairProposalDto
import top.wkbin.zaomeng.data.api.PersonaReviewDto
import top.wkbin.zaomeng.data.api.RelationDetailsDto
import top.wkbin.zaomeng.data.api.SuggestPersonaFieldResponse
import top.wkbin.zaomeng.data.api.WorldMemoryDto
import top.wkbin.zaomeng.domain.run.RunReviewGateway

class PersonaRepositoryImpl(
    private val persona: PersonaClient,
) : PersonaRepository {
    private val avatarCache = mutableMapOf<String, ByteArray>()

    override suspend fun getPersona(runId: String, character: String): PersonaReviewDto = repositoryRequest {
        persona.getReview(runId, character)
    }

    override suspend fun savePersona(
        runId: String,
        character: String,
        completeFields: Map<String, String>,
        reviewNote: String,
    ): PersonaReviewDto = repositoryRequest {
        val payload = buildJsonObject {
            completeFields.forEach { (key, value) -> put(key, JsonPrimitive(value)) }
            put("review_source", JsonPrimitive("android"))
            put("review_note", JsonPrimitive(reviewNote))
        }
        persona.saveReview(runId, character, payload)
    }

    override suspend fun getPersonaQuality(runId: String, character: String): PersonaQualityReportDto = repositoryRequest {
        persona.getQuality(runId, character)
    }

    override suspend fun getPersonaQualityOverview(runId: String): PersonaQualityOverviewDto = repositoryRequest {
        persona.getQualityOverview(runId)
    }

    override suspend fun getPersonaRepairProposal(
        runId: String,
        character: String,
    ): PersonaRepairProposalDto = repositoryRequest {
        persona.getRepairProposal(runId, character)
    }

    override suspend fun deletePersona(runId: String, character: String): DeleteStatusDto = repositoryRequest {
        persona.delete(runId, character).also {
            avatarCache.keys.removeAll { key -> key.startsWith("$runId|$character|") }
        }
    }

    override suspend fun uploadPersonaAvatar(
        runId: String,
        character: String,
        bytes: ByteArray,
    ): PersonaAvatarDto = repositoryRequest {
        require(bytes.isNotEmpty()) { "头像文件为空。" }
        persona.uploadAvatar(runId, character, bytes).also { avatar ->
            avatarCache.keys.removeAll { it.startsWith("$runId|$character|") }
        }
    }

    override suspend fun getPersonaAvatar(
        runId: String,
        character: String,
        version: String,
    ): ByteArray? {
        if (version.isBlank()) return null
        val key = "$runId|$character|$version"
        avatarCache[key]?.let {
            // R7：图片同 URL 请求/缓存命中计数。缓存命中不计入「重复请求」——
            // 真正的重复是「缓存里没有，于是又拉了一次同一张图」。
            ClientDiagnostics.recordImageRequest(url = key, cacheHit = true)
            return it
        }
        val duplicate = ClientDiagnostics.hasRequestedImage(key)
        ClientDiagnostics.recordImageRequest(url = key, cacheHit = false, duplicate = duplicate)
        return repositoryRequest { persona.getAvatar(runId, character)?.also { avatarCache[key] = it } }
    }

    override suspend fun suggestPersonaField(
        runId: String,
        character: String,
        field: String,
    ): SuggestPersonaFieldResponse = repositoryRequest {
        persona.suggestField(runId, character, field)
    }

    override suspend fun getEvolutionProposal(
        runId: String,
        character: String,
        recap: top.wkbin.zaomeng.data.api.StoryRecapDto?,
    ): top.wkbin.zaomeng.data.api.PersonaEvolutionProposalDto = repositoryRequest {
        persona.getEvolutionProposal(runId, character, recap)
    }

    override suspend fun applyEvolution(
        runId: String,
        character: String,
        changes: List<top.wkbin.zaomeng.data.api.PersonaEvolutionChangeDto>,
    ): PersonaReviewDto = repositoryRequest {
        persona.applyEvolution(runId, character, changes)
    }
}

class OriginalKnowledgeRepositoryImpl(
    private val originalKnowledge: OriginalKnowledgeClient,
) : OriginalKnowledgeRepository {
    override suspend fun searchOriginalKnowledge(
        runId: String,
        query: String,
        participants: List<String>,
        pinnedOnly: Boolean,
    ): List<OriginalKnowledgeEntryDto> = repositoryRequest {
        originalKnowledge.search(runId, query, participants, pinnedOnly).items
    }

    override suspend fun updateOriginalKnowledgeBoundary(
        runId: String,
        entryId: String,
        visibility: String,
        knowers: List<String>,
    ) = repositoryRequest {
        originalKnowledge.updateBoundary(runId, entryId, visibility, knowers)
    }

    override suspend fun updateOriginalKnowledgePinned(
        runId: String,
        entryId: String,
        pinned: Boolean,
    ) = repositoryRequest {
        originalKnowledge.updatePinned(runId, entryId, pinned)
    }

    override suspend fun rebuildOriginalKnowledge(runId: String) = repositoryRequest {
        originalKnowledge.rebuild(runId)
    }
}

class RunReviewRepositoryImpl(
    private val relations: RelationsRepository,
    private val worldMemory: WorldMemoryRepository,
    private val persona: PersonaRepository,
) : RunReviewGateway {
    override suspend fun getRelations(runId: String): RelationDetailsDto = relations.getRelations(runId)

    override suspend fun getWorldMemory(runId: String): WorldMemoryDto = worldMemory.getWorldMemory(runId)

    override suspend fun getPersonaQuality(
        runId: String,
        character: String,
    ): PersonaQualityReportDto = persona.getPersonaQuality(runId, character)
}
