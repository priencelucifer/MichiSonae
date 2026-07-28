from michisonae_api.projection import (
    ClaimLost,
    ProjectionUnavailable,
    projection_failure_code,
    projection_retry_delay,
)


def test_projection_retry_delay_is_bounded_exponential_backoff() -> None:
    assert projection_retry_delay(attempt=1, base_seconds=2, maximum_seconds=30) == 2
    assert projection_retry_delay(attempt=4, base_seconds=2, maximum_seconds=30) == 16
    assert projection_retry_delay(attempt=8, base_seconds=2, maximum_seconds=30) == 30


def test_projection_retry_delay_rejects_invalid_attempt() -> None:
    try:
        projection_retry_delay(attempt=0, base_seconds=2, maximum_seconds=30)
    except ValueError as error:
        assert str(error) == "attempt must be at least 1"
    else:
        raise AssertionError("invalid attempt was accepted")


def test_projection_failure_codes_do_not_expose_exception_text() -> None:
    cases = (
        (ClaimLost("secret lease detail"), "projection_claim_lost"),
        (
            ProjectionUnavailable("secret database detail"),
            "projection_processing_unavailable",
        ),
        (RuntimeError("secret internal detail"), "projection_processing_error"),
    )

    for error, expected in cases:
        assert projection_failure_code(error) == expected
        assert "secret" not in projection_failure_code(error)
