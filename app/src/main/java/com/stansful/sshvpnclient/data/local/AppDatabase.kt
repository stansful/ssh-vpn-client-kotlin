package com.stansful.sshvpnclient.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.stansful.sshvpnclient.data.config.SshConfigDao
import com.stansful.sshvpnclient.data.config.SshConfigEntity
import com.stansful.sshvpnclient.data.key.SshPrivateKeyDao
import com.stansful.sshvpnclient.data.key.SshPrivateKeyEntity
import com.stansful.sshvpnclient.data.proxy.ProxyProfileDao
import com.stansful.sshvpnclient.data.proxy.ProxyProfileEntity

@Database(
    entities = [
        SshConfigEntity::class,
        SshPrivateKeyEntity::class,
        ProxyProfileEntity::class,
    ],
    version = 3,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sshConfigDao(): SshConfigDao
    abstract fun sshPrivateKeyDao(): SshPrivateKeyDao
    abstract fun proxyProfileDao(): ProxyProfileDao
}
