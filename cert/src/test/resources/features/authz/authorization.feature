Feature: Authorization - Server Certification
  As a database plugin developer, and as a maintainer of the wiring above the Searcher
  I want authorization proven against the real HTTP surfaces with ordinary credentials
  So that a record written by one caller cannot be read, listed, or deleted by another

  # Why this certification exists, and why it is shaped like this:
  #
  # The other certification suites authorize with a caller entitled to everything.
  # Against such a caller a working authorization filter and a completely inert one
  # produce identical results, which is how this class of bug stayed hidden across
  # several MeshQL implementations. Every scenario below therefore uses two ordinary
  # callers, A and B, with distinct and mutually exclusive credentials.
  #
  # It also drives the real HTTP surfaces - writes through the restlette, reads back
  # through the graphlette, credentials supplied as an Authorization header - because
  # the wiring between those surfaces and the Searcher is exactly where authorization
  # gets dropped. A Repository-level certification structurally cannot see it.
  #
  # "No credentials" remains deliberately permitted: the edge authenticates, the
  # framework authorizes. Scenario 3 pins that semantic down so a fix to the others
  # cannot quietly turn MeshQL into an authenticating server.

  Background:
    Given an authorizing MeshQL server is running with the plugin
    And a fresh workspace

  Scenario: A record written by one caller is readable only by that caller
    When caller "A" creates document "secret"
    Then caller "A" can read document "secret" by id
    And caller "B" cannot read document "secret" by id

  Scenario: A list query returns only the caller's own records
    When caller "A" creates document "a-one"
    And caller "A" creates document "a-two"
    And caller "B" creates document "b-one"
    Then caller "A" listing the workspace sees exactly:
      | a-one |
      | a-two |
    And caller "B" listing the workspace sees exactly:
      | b-one |

  Scenario: A record written without credentials is public
    When caller "anonymous" creates document "notice"
    Then caller "A" can read document "notice" by id
    And caller "B" can read document "notice" by id
    And caller "anonymous" can read document "notice" by id

  Scenario: A caller holding every token still sees everything
    When caller "A" creates document "a-doc"
    And caller "B" creates document "b-doc"
    And caller "anonymous" creates document "open-doc"
    Then caller "star" listing the workspace sees exactly:
      | a-doc |
      | b-doc |
      | open-doc |

  Scenario: The tokens Auth resolved survive the write path
    When caller "A" creates document "stored"
    Then the stored envelope for "stored" has authorized tokens "tenant-a"
    When caller "anonymous" creates document "unowned"
    Then the stored envelope for "unowned" has no authorized tokens

  Scenario: Delete honours tokens
    When caller "A" creates document "a-only"
    And caller "B" deletes document "a-only"
    Then caller "A" can read document "a-only" by id
    When caller "A" deletes document "a-only"
    Then caller "A" cannot read document "a-only" by id

  Scenario: Rewinding time rewinds data, not authorization
    When caller "A" creates document "history"
    And I capture the current timestamp
    And caller "A" renames document "history" to "history-v2"
    Then caller "A" can read document "history" by id at the captured timestamp
    And caller "B" cannot read document "history" by id at the captured timestamp
