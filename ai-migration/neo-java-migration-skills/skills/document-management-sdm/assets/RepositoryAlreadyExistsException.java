package com.example.document;

/**
 * Thrown by {@link DocumentServiceClient#createRepository(String)} when a repository
 * with the requested name already exists.
 *
 * Why this is needed even though SDM also has a notion of "already exists":
 * SDM's `POST /rest/v2/repositories/` is idempotent at the HTTP level — it returns
 * `201 Created` whether or not a repository with that name existed before, so we
 * can't detect the conflict from the response alone. The client therefore
 * pre-checks with `GET /rest/v2/repositories/` and throws this exception so the
 * REST/servlet layer can map it to HTTP 412 Precondition Failed (which is what
 * the Neo `EcmService` would have signalled, and what existing integration tests
 * assert).
 */
public class RepositoryAlreadyExistsException extends Exception {
    public RepositoryAlreadyExistsException(String message) {
        super(message);
    }
}
