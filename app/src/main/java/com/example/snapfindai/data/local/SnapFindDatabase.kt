package com.example.snapfindai.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * [exportSchema] is on and the generated JSON under `app/schemas/` is
 * committed deliberately. It is the only record of what a shipped schema
 * actually looked like, and without it there is no way to move an installed
 * app forward: Room cannot generate an `@AutoMigration` with nothing to diff
 * against, and `MigrationTestHelper` has no starting point to build from.
 *
 * Changing anything about an entity from here means bumping [version] and
 * saying how existing data reaches the new shape. Additive, unambiguous
 * changes (a new nullable column, a new table) can be declared as an
 * `autoMigrations` entry; anything Room cannot infer -- a rename, a type
 * change, a column whose value has to be computed from other columns --
 * needs a hand-written `Migration` registered in `DatabaseModule`.
 *
 * "From here" means from the first release. Until then there is no installed
 * copy of version 1 anywhere, so version 1 is still being defined rather than
 * migrated from: a change is made in place and the exported JSON regenerated.
 * The cost is that a development install has to be reinstalled, because Room
 * compares a hash of the schema and refuses to open a database that doesn't
 * match -- which is the loud failure the missing destructive fallback exists
 * to produce.
 */
@Database(
    entities = [JobEntity::class, MatchedPhotoEntity::class, PendingMatchEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class SnapFindDatabase : RoomDatabase() {
    abstract fun jobDao(): JobDao
}
