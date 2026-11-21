package com.myhealth.domain.engine.strength.video

import com.myhealth.domain.engine.strength.ExerciseCatalog
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** P26.3: the synonym table stays consistent with the catalog. */
class ExerciseSynonymsTest {

    @Test
    fun every_id_is_in_the_catalog() {
        ExerciseSynonyms.BY_ID.keys.forEach { id -> assertNotNull(id, ExerciseCatalog.byId(id)) }
    }

    @Test
    fun no_synonym_points_at_another_exercise() {
        val owners = HashMap<String, String>()
        ExerciseCatalog.ALL.forEach { e -> owners[ExerciseMatcher.key(e.name).replace(" ", "")] = e.id }
        ExerciseCatalog.ALL.forEach { e ->
            ExerciseSynonyms.of(e.id).forEach { name ->
                val key = ExerciseMatcher.key(name).replace(" ", "")
                val prior = owners.put(key, e.id)
                assertTrue("$name: ${prior ?: ""} vs ${e.id}", prior == null || prior == e.id)
            }
        }
    }
}
