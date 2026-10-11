package io.legado.app.data

import android.app.Application
import android.content.ContentValues
import android.database.Cursor
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.google.gson.JsonParser
import io.legado.app.constant.BookType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

/** Exercises the production Room migration chain against populated on-disk historical schemas. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class AppDatabaseUpgradeTest {
    private val context: Application
        get() = RuntimeEnvironment.getApplication()
    private val databaseNames = mutableListOf<String>()

    @After
    fun deleteTestDatabases() {
        databaseNames.forEach(context::deleteDatabase)
    }

    @Test
    fun version107PreservesComicBooksProgressChaptersCacheAndHighlightRules() {
        lateinit var originalRows: Map<String, TableSnapshot>
        val name = createHistoricalDatabase(107) { db ->
            insertBook(db, LOCAL_CBZ, BookType.local or BookType.image, BookType.localTag)
            insertBook(db, ONLINE_COMIC, BookType.image, "https://source.example.invalid")
            insertChapter(db, LOCAL_CBZ, "0002_第二章/0001.jpg", "第二章")
            insertChapter(db, ONLINE_COMIC, "$ONLINE_COMIC/chapter/2", "第二章")
            db.execSQL(
                "INSERT INTO caches (`key`, `value`, deadline) VALUES (?, ?, ?)",
                arrayOf<Any>("comic-cache-key", "cached chapter content", 4_102_444_800_000L),
            )
            insertHighlightRule(db)
            originalRows = PRESERVED_TABLES.associateWith { snapshot(db, it) }
            assertEquals(107, db.version)
        }

        withCurrentDatabase(name) { db ->
            assertEquals(130, db.version)
            assertPreserved(db, originalRows)
            assertHighlightOffsets(db, 0.0, 0.0)
            assertEquals(
                0.0,
                number(db, "SELECT letterSpacingBefore FROM highlightRules WHERE id = 'custom-highlight'"),
                0.0,
            )
            listOf(
                "chapter_role_assignments", "cast_characters", "voice_pools",
                "book_cast_memory", "bgm_scene_marks", "read_aloud_audio_downloads",
                "regex_cast_rules", "regex_cast_groups",
            ).forEach { table ->
                db.query("SELECT COUNT(*) FROM `$table`").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(table, 0L, cursor.getLong(0))
                }
            }
        }

        // Reopening also validates the post-migration Room identity hash on a normal launch.
        withCurrentDatabase(name) { db ->
            assertEquals(130, db.version)
            assertPreserved(db, originalRows)
        }
    }

    @Test
    fun version124SplitsLegacyHighlightOffsetWithoutChangingOtherFields() {
        lateinit var originalRule: TableSnapshot
        val name = createHistoricalDatabase(124) { db ->
            insertHighlightRule(db)
            db.execSQL(
                """UPDATE highlightRules SET bgLengthOffset = 8.0,
                    letterSpacingBefore = 1.25, letterSpacingAfter = 2.5,
                    lineSpacingTop = 3.0, lineSpacingBottom = 4.0
                    WHERE id = 'custom-highlight'""".trimIndent(),
            )
            val oldRule = snapshot(db, "highlightRules")
            originalRule = snapshot(db, "highlightRules", oldRule.columns - "bgLengthOffset")
        }

        withCurrentDatabase(name) { db ->
            assertEquals(130, db.version)
            assertEquals(originalRule, snapshot(db, "highlightRules", originalRule.columns))
            assertHighlightOffsets(db, 4.0, 4.0)
            db.query("PRAGMA table_info(highlightRules)").use { cursor ->
                val columnIndex = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) {
                    assertTrue(cursor.getString(columnIndex) != "bgLengthOffset")
                }
            }
        }
        withCurrentDatabase(name) { db ->
            assertHighlightOffsets(db, 4.0, 4.0)
        }
    }

    private fun createHistoricalDatabase(
        version: Int,
        populate: (SupportSQLiteDatabase) -> Unit,
    ): String {
        // Keep native SQLite's -wal/-shm paths below Windows path limits in Robolectric sandboxes.
        val name = "upgrade-$version-${UUID.randomUUID().toString().take(8)}"
        databaseNames += name
        val schema = JsonParser.parseString(findSchema(version).readText())
            .asJsonObject.getAsJsonObject("database")
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(version) {
                    override fun onConfigure(db: SupportSQLiteDatabase) {
                        db.setForeignKeyConstraintsEnabled(true)
                    }

                    override fun onCreate(db: SupportSQLiteDatabase) {
                        schema.getAsJsonArray("entities").forEach { element ->
                            val entity = element.asJsonObject
                            val tableName = entity.get("tableName").asString
                            fun execute(sql: String) {
                                db.execSQL(sql.replace("\${TABLE_NAME}", tableName))
                            }
                            execute(entity.get("createSql").asString)
                            entity.getAsJsonArray("indices")?.forEach { index ->
                                execute(index.asJsonObject.get("createSql").asString)
                            }
                        }
                        schema.getAsJsonArray("views")?.forEach { element ->
                            val view = element.asJsonObject
                            db.execSQL(
                                view.get("createSql").asString.replace(
                                    "\${VIEW_NAME}", view.get("viewName").asString,
                                ),
                            )
                        }
                        schema.getAsJsonArray("setupQueries").forEach { query ->
                            db.execSQL(query.asString)
                        }
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                        error("Historical fixture must only create version $version, not migrate it")
                    }
                })
                .build(),
        )
        try {
            populate(helper.writableDatabase)
        } finally {
            helper.close()
        }
        return name
    }

    private fun findSchema(version: Int): File {
        val relativePath = "schemas/io.legado.app.data.AppDatabase/$version.json"
        return generateSequence(File(requireNotNull(System.getProperty("user.dir"))).absoluteFile) { it.parentFile }
            .flatMap { directory ->
                sequenceOf(File(directory, relativePath), File(directory, "app/$relativePath"))
            }
            .firstOrNull(File::isFile)
            ?: error("Cannot locate committed Room schema $version from the test working directory")
    }

    private fun withCurrentDatabase(name: String, verify: (SupportSQLiteDatabase) -> Unit) {
        val room = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(*DatabaseMigrations.migrations)
            .allowMainThreadQueries()
            .build()
        try {
            // Deliberately no destructive fallback or production asset-import callback.
            verify(room.openHelper.writableDatabase)
        } finally {
            room.close()
        }
    }

    private fun insertBook(db: SupportSQLiteDatabase, url: String, type: Int, origin: String) {
        val values = ContentValues().apply {
            put("bookUrl", url)
            put("tocUrl", "$url/toc")
            put("origin", origin)
            put("originName", "测试来源")
            put("name", if (url == LOCAL_CBZ) "本地漫画" else "书源漫画")
            put("author", "测试作者")
            put("type", type)
            put("group", 7L)
            put("isPrivate", 1)
            put("coverUrl", "content://comic-covers/original")
            put("customCoverUrl", "content://comic-covers/custom")
            put("intro", "漫画简介")
            put("customIntro", "自定义漫画简介")
            put("totalChapterNum", 8)
            put("durChapterTitle", "第二章")
            put("durChapterIndex", 1)
            put("durChapterPos", 35)
            put("durChapterTime", 1_792_000_000_000L)
            put("variable", "{\"remoteBookId\":\"comic-42\"}")
            put("readConfig", "{\"reverseToc\":true}")
            put("syncTime", 1_792_000_000_001L)
        }
        insertFixture(db, "books", values)
    }

    private fun insertChapter(db: SupportSQLiteDatabase, bookUrl: String, url: String, title: String) {
        val values = ContentValues().apply {
            put("url", url)
            put("title", title)
            put("isVolume", 0)
            put("baseUrl", bookUrl)
            put("bookUrl", bookUrl)
            put("index", 1)
            put("isVip", 0)
            put("isPay", 0)
            put("resourceUrl", "cached-page-list")
            put("variable", "{\"pageCount\":316}")
            put("reviewImg", "content://comic-pages/cached")
        }
        insertFixture(db, "chapters", values)
    }

    private fun insertHighlightRule(db: SupportSQLiteDatabase) {
        val values = ContentValues().apply {
            put("id", "custom-highlight")
            put("name", "自定义高亮")
            put("pattern", "测试人物")
            put("sampleText", "测试人物的对话")
            put("targetScope", 2)
            put("enabled", 1)
            put("position", 9)
            put("textColor", 0xff123456.toInt())
            put("bgColor", 0xff654321.toInt())
            put("underlineMode", 1)
            put("underlineColor", 0xff345678.toInt())
            put("underlineWidth", 1.25)
            put("underlineOffset", 2.5)
            put("bgImage", "custom-bubble.png")
            put("bgImageFit", 1)
            put("bgImageScale", 0.75)
            put("configName", "custom-style")
            put("fontWeight", 600)
            put("isItalic", 1)
            put("fontSizeOffset", 2)
            put("npLeft", 3.25)
            put("npRight", 4.5)
            put("npTop", 5.0)
            put("npBottom", 6.0)
            put("manualNineSlice", 1)
        }
        insertFixture(db, "highlightRules", values)
    }

    private fun insertFixture(db: SupportSQLiteDatabase, table: String, values: ContentValues) {
        val columns = values.keySet().toList()
        val names = columns.joinToString { "`$it`" }
        val placeholders = columns.joinToString { "?" }
        db.execSQL(
            "INSERT INTO `$table` ($names) VALUES ($placeholders)",
            columns.map { values[it] }.toTypedArray(),
        )
    }

    private fun assertPreserved(db: SupportSQLiteDatabase, originals: Map<String, TableSnapshot>) {
        originals.forEach { (table, expected) ->
            assertEquals(table, expected, snapshot(db, table, expected.columns))
        }
    }

    private fun snapshot(
        db: SupportSQLiteDatabase,
        table: String,
        columns: List<String>? = null,
    ): TableSnapshot {
        val projection = columns?.joinToString { "`$it`" } ?: "*"
        return db.query("SELECT $projection FROM `$table` ORDER BY rowid").use { cursor ->
            val rows = buildList {
                while (cursor.moveToNext()) {
                    add((0 until cursor.columnCount).map { index ->
                        when (cursor.getType(index)) {
                            Cursor.FIELD_TYPE_NULL -> null
                            Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
                            Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(index)
                            Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(index).toList()
                            else -> cursor.getString(index)
                        }
                    })
                }
            }
            TableSnapshot(cursor.columnNames.toList(), rows)
        }
    }

    private fun assertHighlightOffsets(db: SupportSQLiteDatabase, left: Double, right: Double) {
        assertEquals(left, number(db, "SELECT bgLengthOffsetLeft FROM highlightRules"), 0.0)
        assertEquals(right, number(db, "SELECT bgLengthOffsetRight FROM highlightRules"), 0.0)
    }

    private fun number(db: SupportSQLiteDatabase, query: String): Double =
        db.query(query).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getDouble(0)
        }

    private data class TableSnapshot(val columns: List<String>, val rows: List<List<Any?>>)

    companion object {
        private const val LOCAL_CBZ = "content://local-comics/downloaded.cbz"
        private const val ONLINE_COMIC = "https://comic.example.invalid/book/42"
        private val PRESERVED_TABLES = listOf("books", "chapters", "caches", "highlightRules")
    }
}
