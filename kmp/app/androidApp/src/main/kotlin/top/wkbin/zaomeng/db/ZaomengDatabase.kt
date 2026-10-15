package top.wkbin.zaomeng.db

import androidx.room3.Database
import androidx.room3.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import top.wkbin.zaomeng.platform.platformIoDispatcher

/**
 * 统一持久化数据库：
 * - documents：通用文档存储（二进制/未建模产物，业务数据全文）。
 * - runs / sessions / messages / cards / personas：领域实体表（v2 起），
 *   由 StorageService 写入时同步维护，列表/检索读取走实体。
 */
@Database(
    entities = [
        DocumentEntity::class,
        RunEntity::class,
        SessionEntity::class,
        MessageEntity::class,
        CardEntity::class,
        PersonaEntity::class,
    ],
    version = 2,
)
abstract class ZaomengDatabase : RoomDatabase() {
    abstract fun documentDao(): DocumentDao

    abstract fun domainDao(): DomainDao
}

/*
 * 单模块（纯 Android）后不再使用 Room 的 KMP 实例化机制（@ConstructedBy +
 * expect RoomDatabaseConstructor）：那套需要一个 expect object 来挂编译器生成的
 * actual 实现，而 expect/actual 在非 KMP 模块里不可用。
 * 改用标准 Android 路径：Room.databaseBuilder(context, ZaomengDatabase::class.java, name)，
 * 由 Room 生成 ZaomengDatabase_Impl 并通过构造器实例化。
 */
/** 通用构建：捆绑 SQLite 驱动 + IO 调度器（平台差异只剩 Builder 的路径/Context）。 */
fun buildZaomengDatabase(builder: RoomDatabase.Builder<ZaomengDatabase>): ZaomengDatabase =
    builder
        // 不兼容升级：旧版（v1 documents-only）数据库直接重建，不迁移老数据。
        .fallbackToDestructiveMigration(dropAllTables = true)
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(platformIoDispatcher)
        .build()
