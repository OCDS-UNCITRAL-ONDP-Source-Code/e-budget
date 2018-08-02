package com.procurement.budget.controller

import com.procurement.budget.exception.EnumException
import com.procurement.budget.exception.ErrorException
import com.procurement.budget.model.bpe.ResponseDetailsDto
import com.procurement.budget.model.bpe.ResponseDto
import org.springframework.http.HttpStatus.OK
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.web.bind.annotation.ResponseStatus

@ControllerAdvice
class ControllerExceptionHandler {

    @ResponseBody
    @ResponseStatus(OK)
    @ExceptionHandler(Exception::class)
    fun exception(ex: Exception): ResponseDto {
        return when (ex) {
            is ErrorException -> ResponseDto(false, getErrors(ex.code, ex.msg), null)
            is EnumException -> ResponseDto(false, getErrors(ex.code, ex.msg), null)
            else -> ResponseDto(false, getErrors("Exception", ex.message), null)
        }
    }


    private fun getErrors(code: String, error: String?) =
            listOf(ResponseDetailsDto(code = "400.10.$code", message = error!!))

}
