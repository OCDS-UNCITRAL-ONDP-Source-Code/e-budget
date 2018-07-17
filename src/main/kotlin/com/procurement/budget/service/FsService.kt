package com.procurement.budget.service

import com.procurement.budget.dao.EiDao
import com.procurement.budget.dao.FsDao
import com.procurement.budget.exception.ErrorException
import com.procurement.budget.exception.ErrorType
import com.procurement.budget.model.bpe.ResponseDto
import com.procurement.budget.model.dto.ei.Ei
import com.procurement.budget.model.dto.ei.OrganizationReferenceEi
import com.procurement.budget.model.dto.ei.ValueEi
import com.procurement.budget.model.dto.fs.*
import com.procurement.budget.model.dto.fs.request.FsCreate
import com.procurement.budget.model.dto.fs.request.FsUpdate
import com.procurement.budget.model.dto.fs.response.EiForFs
import com.procurement.budget.model.dto.fs.response.EiForFsBudget
import com.procurement.budget.model.dto.fs.response.EiForFsPlanning
import com.procurement.budget.model.dto.fs.response.FsResponse
import com.procurement.budget.model.dto.ocds.*
import com.procurement.budget.model.dto.ocds.Currency
import com.procurement.budget.model.entity.FsEntity
import com.procurement.budget.utils.toDate
import com.procurement.budget.utils.toJson
import com.procurement.budget.utils.toObject
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.*

interface FsService {

    fun createFs(cpId: String,
                 owner: String,
                 dateTime: LocalDateTime,
                 fsDto: FsCreate): ResponseDto

    fun updateFs(cpId: String,
                 ocId: String,
                 token: String,
                 owner: String,
                 fsDto: FsUpdate): ResponseDto
}

@Service
class FsServiceImpl(private val fsDao: FsDao,
                    private val eiDao: EiDao,
                    private val generationService: GenerationService) : FsService {

    override fun createFs(cpId: String,
                          owner: String,
                          dateTime: LocalDateTime,
                          fsDto: FsCreate): ResponseDto {
        validatePeriod(fsDto.planning.budget.period)
        validateEuropeanUnionFunding(fsDto.planning.budget.isEuropeanUnionFunded!!, fsDto.planning.budget.europeanUnionFunding)
        val eiEntity = eiDao.getByCpId(cpId) ?: throw ErrorException(ErrorType.EI_NOT_FOUND)
        val ei = toObject(Ei::class.java, eiEntity.jsonData)
        checkPeriodWithEi(ei.planning.budget.period, fsDto.planning.budget.period)
        checkCurrency(ei, fsDto.planning.budget.amount.currency)

        val tenderStatusFs: TenderStatus
        val funderFs: OrganizationReferenceFs?
        val sourceEntityFs: SourceEntityFs
        val verifiedFs: Boolean
        if (fsDto.buyer != null) {
            funderFs = fsDto.buyer
            funderFs.apply { id = identifier.scheme + SEPARATOR + identifier.id }
            sourceEntityFs = getSourceEntity(funderFs)
            verifiedFs = true
            tenderStatusFs = TenderStatus.ACTIVE
        } else {
            funderFs = null
            sourceEntityFs = getSourceEntity(getFounderFromEi(ei.buyer))
            verifiedFs = false
            tenderStatusFs = TenderStatus.PLANNING
        }
        val ocid = getOcId(cpId)
        val budgetDto = fsDto.planning.budget
        val fs = Fs(
                ocid = ocid,
                token = null,
                tender = TenderFs(
                        id = ocid,
                        status = tenderStatusFs,
                        statusDetails = TenderStatusDetails.EMPTY,
                        procuringEntity = null),
                planning = PlanningFs(
                        budget = BudgetFs(
                                id = budgetDto.id,
                                description = budgetDto.description,
                                period = budgetDto.period,
                                amount = budgetDto.amount,
                                isEuropeanUnionFunded = budgetDto.isEuropeanUnionFunded!!,
                                europeanUnionFunding = budgetDto.europeanUnionFunding,
                                verificationDetails = null,
                                sourceEntity = sourceEntityFs,
                                verified = verifiedFs,
                                project = budgetDto.project,
                                projectID = budgetDto.projectID,
                                uri = budgetDto.uri
                        ),
                        rationale = fsDto.planning.rationale
                ),
                funder = funderFs,
                payer = fsDto.tender.procuringEntity.apply { id = identifier.scheme + SEPARATOR + identifier.id }
        )
        val fsEntity = getEntity(cpId, fs, owner, dateTime)
        fsDao.save(fsEntity)
        fs.token = fsEntity.token.toString()
        //ei
        val totalAmount = fsDao.getTotalAmountByCpId(cpId) ?: BigDecimal.ZERO
        ei.planning.budget.amount = ValueEi(
                amount = totalAmount,
                currency = fs.planning.budget.amount.currency)
        eiEntity.jsonData = toJson(ei)
        eiDao.save(eiEntity)
        return ResponseDto(true, null, FsResponse(getEiForFs(ei), fs))
    }

    override fun updateFs(cpId: String,
                          ocId: String,
                          token: String,
                          owner: String,
                          fsDto: FsUpdate): ResponseDto {
        validatePeriod(fsDto.planning.budget.period)
        validateEuropeanUnionFunding(fsDto.planning.budget.isEuropeanUnionFunded!!, fsDto.planning.budget.europeanUnionFunding)
        val fsEntity = fsDao.getByCpIdAndToken(cpId, UUID.fromString(token))?: throw ErrorException(ErrorType.FS_NOT_FOUND)
        if (fsEntity.ocId != ocId) throw ErrorException(ErrorType.INVALID_OCID)
        if (fsEntity.owner != owner) throw ErrorException(ErrorType.INVALID_OWNER)
        val fs = toObject(Fs::class.java, fsEntity.jsonData)
        val eiEntity = eiDao.getByCpId(cpId) ?: throw ErrorException(ErrorType.EI_NOT_FOUND)
        val ei = toObject(Ei::class.java, eiEntity.jsonData)
        checkPeriodWithEi(ei.planning.budget.period, fsDto.planning.budget.period)
        checkCurrency(ei, fsDto.planning.budget.amount.currency)
        if (fs.tender.statusDetails != TenderStatusDetails.EMPTY) throw ErrorException(ErrorType.INVALID_STATUS)
        when (fs.tender.status) {
            TenderStatus.ACTIVE -> updateFs(fs, fsDto)
            TenderStatus.PLANNING -> {
                updateFs(fs, fsDto)
                fs.planning.budget.id = fsDto.planning.budget.id
            }
            else -> throw ErrorException(ErrorType.INVALID_STATUS)
        }
        fsEntity.jsonData = toJson(fs)
        fsDao.save(fsEntity)
        val totalAmount = fsDao.getTotalAmountByCpId(cpId) ?: BigDecimal.ZERO
        var eiForFs: EiForFs? = null
        if (totalAmount != ei.planning.budget.amount?.amount) {
            ei.planning.budget.amount?.amount = totalAmount
            eiEntity.jsonData = toJson(ei)
            eiDao.save(eiEntity)
            eiForFs = getEiForFs(ei)
        }
        return ResponseDto(true, null, FsResponse(eiForFs, fs))
    }

    private fun updateFs(fs: Fs, fsUpdate: FsUpdate) {

        fs.planning.apply {
            rationale = fsUpdate.planning.rationale
            budget.apply {
                period = fsUpdate.planning.budget.period
                description = fsUpdate.planning.budget.description
                amount.amount = fsUpdate.planning.budget.amount.amount
                project = fsUpdate.planning.budget.project
                projectID = fsUpdate.planning.budget.projectID
                uri = fsUpdate.planning.budget.uri
                if (isEuropeanUnionFunded) {
                    europeanUnionFunding = fsUpdate.planning.budget.europeanUnionFunding
                }
            }
        }
    }

    private fun validatePeriod(period: Period) {
        if (!period.startDate.isBefore(period.endDate))
            throw ErrorException(ErrorType.INVALID_PERIOD)
    }

    private fun validateEuropeanUnionFunding(isEuropeanUnionFunded: Boolean,
                                             europeanUnionFunding: EuropeanUnionFunding?) {
        if (isEuropeanUnionFunded && europeanUnionFunding == null) throw ErrorException(ErrorType.INVALID_EUROPEAN)
    }

    private fun checkPeriodWithEi(eiPeriod: Period, fsPeriod: Period) {
        val (eiStartDate, eiEndDate) = eiPeriod
        val (fsStartDate, fsEndDate) = fsPeriod
        val fsPeriodValid = (fsStartDate.isAfter(eiStartDate) || fsStartDate.isEqual(eiStartDate))
                && (fsEndDate.isBefore(eiEndDate) || fsEndDate.isEqual(eiEndDate))
        if (!fsPeriodValid) throw ErrorException(ErrorType.INVALID_PERIOD)
    }

    private fun checkCurrency(ei: Ei, fsCurrency: Currency) {
        val eiCurrency = ei.planning.budget.amount?.currency
        if (eiCurrency != null) {
            if (eiCurrency != fsCurrency) throw ErrorException(ErrorType.INVALID_CURRENCY)
        }
    }

    private fun getFounderFromEi(buyer: OrganizationReferenceEi): OrganizationReferenceFs {
        return OrganizationReferenceFs(
                id = buyer.id,
                name = buyer.name,
                identifier = buyer.identifier,
                address = buyer.address,
                additionalIdentifiers = buyer.additionalIdentifiers ?: hashSetOf(),
                contactPoint = buyer.contactPoint
        )
    }

    private fun getSourceEntity(funder: OrganizationReferenceFs): SourceEntityFs {
        return SourceEntityFs(
                id = funder.id!!,
                name = funder.name)
    }

    private fun getOcId(cpId: String): String {
        return cpId + FS_SEPARATOR + generationService.getNowUtc()
    }

    private fun getEiForFs(ei: Ei): EiForFs {
        return EiForFs(
                planning = EiForFsPlanning(
                        budget = EiForFsBudget(
                                amount = ei.planning.budget.amount
                        )
                )
        )
    }

    private fun getEntity(cpId: String, fs: Fs, owner: String, dateTime: LocalDateTime): FsEntity {
        val ocId = fs.ocid
        return FsEntity(
                cpId = cpId,
                ocId = ocId,
                token = generationService.generateRandomUUID(),
                owner = owner,
                createdDate = dateTime.toDate(),
                jsonData = toJson(fs),
                amount = fs.planning.budget.amount.amount,
                amountReserved = BigDecimal.ZERO
        )
    }

    companion object {
        private val SEPARATOR = "-"
        private val FS_SEPARATOR = "-FS-"
    }

}
