#!/usr/bin/python -u
"""Copyright (C) 2013 Pepperdata Inc. - All rights reserved.

pdreview-git-snapshot - Uploads a new revision of cached diffs for a review.
"""

import os
import sys

from pdreview_git_tools import any_cached_diffs
from pdreview_git_tools import assert_git_tree
from pdreview_git_tools import assert_no_uncached_diffs
from pdreview_git_tools import capture_git_cached_diffs
from pdreview_git_tools import get_git_root
from pdreview_git_tools import is_in_git_tree
from pdreview_git_tools import post_review
from pdreview_git_tools import post_snapshot

def main(argv = None):
    if len(sys.argv) <= 1:
        print "Usage: ", sys.argv[0], "review_id"
        sys.exit(1)
    assert_git_tree()
    assert_no_uncached_diffs()
    diff_file = capture_git_cached_diffs()
    print "diff_file = ", diff_file
    post_snapshot(diff_file, sys.argv[1])
    return 0


if __name__ == "__main__":
    # TODO(jesse): Combine with other pdreview-git commands.
    sys.exit(main())
