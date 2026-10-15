package top.wkbin.zaomeng.db

import android.content.Context
import androidx.room3.Room
import androidx.room3.RoomDatabase

/** Android：数据库文件放在应用私有数据库目录（getDatabasePath）。 */
fun getDatabaseBuilder(context: Context): RoomDatabase.Builder<ZaomengDatabase> {
    val appContext = context.applicationContext
    return Room.databaseBuilder(
        context = appContext,
        klass = ZaomengDatabase::class.java,
        name = appContext.getDatabasePath("zaomeng.db").absolutePath,
    )
}

/**
 * 显式路径建库：**测试**与需要自定义数据库位置的场景使用。
 *
 * 刻意不要求 Context：`buildZaomengDatabase` 已显式设置 `BundledSQLiteDriver()`，
 * 驱动的加载与数据库文件的创建都不再依赖 Android 的应用目录解析，
 * 因此单元测试（JVM + Android 单测）可以直接按路径建库，不必引入 Robolectric。
 */
fun getDatabaseBuilder(path: okio.Path): RoomDatabase.Builder<ZaomengDatabase> =
    Room.databaseBuilder<ZaomengDatabase>(name = path.toString())
