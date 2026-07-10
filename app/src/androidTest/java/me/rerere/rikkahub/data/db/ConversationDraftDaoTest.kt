package me.rerere.rikkahub.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.db.entity.ConversationDraftEntity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConversationDraftDaoTest {
    private lateinit var database: AppDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun draftCanExistBeforeConversationIsPersisted() = runBlocking {
        val conversationId = "future-conversation"
        val draft = ConversationDraftEntity(
            conversationId = conversationId,
            partsJson = "[]",
            updatedAt = 123L,
        )

        database.conversationDraftDao().upsert(draft)

        assertEquals(draft, database.conversationDraftDao().get(conversationId))
    }
}
