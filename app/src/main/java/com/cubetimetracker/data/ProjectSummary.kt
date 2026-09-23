package com.cubetimetracker.data

/**
 * Read-only aggregate used by the home screen.
 * Keeps a project together with its accumulated tracked time and session count.
 */
data class ProjectSummary(
    val project: Project,
    val totalMillis: Long,
    val sessionCount: Int
)
