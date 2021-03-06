package com.procurement.budget.dao

import com.datastax.driver.core.Session
import com.datastax.driver.core.querybuilder.QueryBuilder.eq
import com.datastax.driver.core.querybuilder.QueryBuilder.select
import org.springframework.stereotype.Service

@Service
class RulesDao(private val session: Session) {

    fun getValue(country: String, parameter: String): String? {
        val query = select()
                .column(VALUE)
                .from(RULES_TABLE)
                .where(eq(CONTRY, country))
                .and(eq(PARAMETER, parameter))
                .limit(1)
        val row = session.execute(query).one()
        return if (row != null) return row.getString(VALUE)
        else null
    }

    companion object {
        private const val RULES_TABLE = "budget_rules"
        private const val CONTRY = "country"
        private const val PARAMETER = "parameter"
        private const val VALUE = "value"
    }
}
