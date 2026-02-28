package com.mobilekinetic.agent.di

import com.mobilekinetic.agent.data.db.MobileKineticDatabase
import com.mobilekinetic.agent.data.db.dao.ProviderDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module for AI provider dependencies.
 *
 * Only [ProviderDao] requires an explicit `@Provides` binding because it
 * comes from the Room database instance.  The following classes use
 * `@Inject constructor` and are automatically discovered by Hilt:
 *  - [com.mobilekinetic.agent.provider.ProviderConfigStore]
 *  - [com.mobilekinetic.agent.provider.ProviderRegistry]
 *  - [com.mobilekinetic.agent.provider.impl.ClaudeCliProvider]
 */
@Module
@InstallIn(SingletonComponent::class)
object ProviderModule {

    @Provides
    @Singleton
    fun provideProviderDao(database: MobileKineticDatabase): ProviderDao =
        database.providerDao()
}
