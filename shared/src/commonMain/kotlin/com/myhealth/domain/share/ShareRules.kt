package com.myhealth.domain.share

/**
 * P27: the Firestore security rules the creator of a library pastes into the Firebase console.
 *
 * They make the server enforce what the app promises:
 * - nothing is readable without a `members` document, and that document can only be written with
 *   the library's join code, which is in these rules and in the connection code, nowhere else;
 * - an item can only be created as yourself, and only its owner may change or delete it, so
 *   another member can never overwrite your content, whatever app they run.
 */
object ShareRules {

    fun text(joinCode: String): String {
        require(joinCode.all { it.isLetterOrDigit() }) { "join code must be alphanumeric" }
        return """
            rules_version = '2';
            service cloud.firestore {
              match /databases/{database}/documents {
                function signedIn() {
                  return request.auth != null;
                }
                function member() {
                  return signedIn() && exists(/databases/${'$'}(database)/documents/members/${'$'}(request.auth.uid));
                }
                match /members/{uid} {
                  allow read: if member();
                  allow create, update: if signedIn() && request.auth.uid == uid
                    && request.resource.data.joinCode == '$joinCode'
                    && request.resource.data.name is string
                    && request.resource.data.name.size() <= 40;
                  allow delete: if signedIn() && request.auth.uid == uid;
                }
                match /items/{id} {
                  allow read: if member();
                  allow create: if member()
                    && request.resource.data.ownerUid == request.auth.uid
                    && request.resource.data.payload.size() < 200000;
                  allow update: if member()
                    && resource.data.ownerUid == request.auth.uid
                    && request.resource.data.ownerUid == request.auth.uid
                    && request.resource.data.payload.size() < 200000;
                  allow delete: if member() && resource.data.ownerUid == request.auth.uid;
                }
              }
            }
        """.trimIndent() + "\n"
    }
}
