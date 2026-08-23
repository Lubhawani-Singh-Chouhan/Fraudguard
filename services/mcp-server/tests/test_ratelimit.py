from ratelimit import RateLimitError, SessionRateLimiter


def test_allows_under_cap_then_blocks():
    limiter = SessionRateLimiter(max_calls=3, window_seconds=60)
    limiter.check("s1")
    limiter.check("s1")
    limiter.check("s1")
    try:
        limiter.check("s1")
        assert False, "expected RateLimitError"
    except RateLimitError:
        pass
    limiter.check("other-session")
