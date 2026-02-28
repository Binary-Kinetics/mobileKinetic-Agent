package com.mobilekinetic.agent.data.db

import androidx.room.TypeConverter
import com.mobilekinetic.agent.data.model.MessageRole

class Converters {
    @TypeConverter
    fun fromMessageRole(role: MessageRole): String = role.name

    @TypeConverter
    fun toMessageRole(value: String): MessageRole = MessageRole.valueOf(value)
}
