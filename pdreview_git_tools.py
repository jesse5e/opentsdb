#!/usr/bin/python -u
"""Copyright (C) 2013 Pepperdata Inc. - All rights reserved.

pdreview_git_tools - Git helper functions for code review.

"""

from subprocess import PIPE

import json
import optparse
import os
import re
import subprocess
import sys
import tempfile
import textwrap
import urllib2
import datetime
import random


GIT_DIFF_CACHED = ["git", "diff", "--find-copies", "--cached", "--full-index"]
GIT_DIFF_UNCACHED = ["git", "diff", "--full-index"]
GIT_FETCH_DRY_RUN = ["git", "fetch", "--dry-run", "--all"]
POST_REVIEW_TECH = ["post-review", "-o", "--target-groups=tech"]
POST_REVIEW_SNAPSHOT = ["post-review", "--diff-only"]


def is_in_git_tree():
    p = subprocess.Popen(["git", "rev-parse", "--is-inside-work-tree"],
                          stdout=PIPE, stderr=None)
    (out, _) = p.communicate()
    return p.returncode == 0 and "true" == out.strip()


def assert_git_tree():
    """ Makes sure that we are in a git tree.

    Raises:
        IOError if failed to run a git command.
        ValueError if we are not in a git tree.
    """
    if not is_in_git_tree():
        raise ValueError("We are not in a git tree!!!")


def get_git_root():
    """ Show the absolute path of the top-level directory.

    Returns:
        The absolute path of the top-level directory.

    Raises:
        IOError if it is not in a git tree.
    """
    p = subprocess.Popen(["git", "rev-parse", "--show-toplevel"], stdout=PIPE)
    (out, _) = p.communicate()
    if p.returncode != 0:
        raise IOError("Can't get the git root directory.")
    return out.strip()


def any_cached_diffs():
    """ Returns True if there is any cached diffs for commit.

    Raises:
        IOError if failed to run a git command.
    """
    p = subprocess.Popen(GIT_DIFF_CACHED, stdout=PIPE, stderr=None)
    (out, _) = p.communicate()
    if p.returncode != 0:
        raise IOError("Failed to run a git command - " + str(GIT_DIFF_CACHED))
    return len(out.strip()) > 0


def any_uncached_diffs():
    """ Returns True if there is any uncached diffs for commit.

    Raises:
        IOError if failed to run a git command.
    """
    p = subprocess.Popen(GIT_DIFF_UNCACHED, stdout=PIPE, stderr=None)
    (out, _) = p.communicate()
    if p.returncode != 0:
        raise IOError("Failed to run a git command - " + \
                      str(GIT_DIFF_UNCACHED,))
    return len(out.strip()) > 0


def assert_no_cached_diffs():
    """ Raises an exception if there is any cached diffs.

    Raises:
        IOError if failed to run a git command.
        ValueError if there is any cached diffs.
    """
    if any_cached_diffs():
        raise ValueError("We don't support cached diffs. " + \
              "Move them to uncached diffs - 'git reset HEAD <file>...'.")


def assert_no_uncached_diffs():
    """ Raises an exception if there is any uncached diffs.

    Raises:
        IOError if failed to run a git command.
        ValueError if there is any uncached diffs.
    """
    if any_uncached_diffs():
        raise ValueError("We don't support uncached diffs. " + \
              "Move them to cached diffs - 'git add <file>...'.")


def any_incoming_changes():
    """ Returns True if there is any incoming changes.

    Raises:
        IOError if failed to run a git command.
    """
    p = subprocess.Popen(GIT_FETCH_DRY_RUN, stdout=PIPE, stderr=PIPE)
    (out, err) = p.communicate()
    if p.returncode != 0:
        raise IOError("Failed to run a git command - " + \
                      str(GIT_FETCH_DRY_RUN,))
    return re.search("\s[0-9A-Fa-f]+\\.\\.[0-9A-Fa-f]+\s", err)


def assert_no_incoming_changes():
    """ Raises an exception if there is any incoming_changes.

    Raises:
        IOError if failed to run a git command.
        ValueError if there is any incoming_changes.
    """
    if any_incoming_changes():
        raise ValueError("Commit/Push requires no incoming_changes. " + \
              "Update local repository - 'git pull'.")


def capture_git_uncached_diffs():
    """ Capture diffs to a file at '/tmp' and returns the path.

    Returns:
        File path to the diff file.

    Raises:
        IOError if failed to run a git command.
    """
    diff_file = datetime.datetime.now().strftime(\
        "/tmp/" + os.getlogin() + ".%Y%m%d%H%M%S.%f.diff.txt")
    with open(diff_file, "w") as f:
        rc = subprocess.call(GIT_DIFF_UNCACHED, stdout=f, stderr=None)
    if rc != 0:
        raise IOError("Failed to run a git command - " + \
                      str(GIT_DIFF_UNCACHED))
    return diff_file


def capture_git_cached_diffs():
    """ Capture cached diffs to a file at '/tmp' and returns the path.

    Returns:
        File path to the diff file.

    Raises:
        IOError if failed to run a git command.
    """
    diff_file = datetime.datetime.now().strftime(\
        "/tmp/" + os.getlogin() + ".%Y%m%d%H%M%S.%f.diff.txt")
    with open(diff_file, "w") as f:
        rc = subprocess.call(GIT_DIFF_CACHED, stdout=f, stderr=None)
    if rc != 0:
        raise IOError("Failed to run a git command - " + str(GIT_DIFF_CACHED))
    return diff_file


def _assert_diff_file(diff_file):
    if not os.access(diff_file, os.F_OK):
        raise ValueError("Failed to post a review because " + diff_file +
                         " doesn't exist.")
    statinfo = os.stat(diff_file)
    if statinfo.st_size == 0:
        raise ValueError("Failed to post a review because " + diff_file +
                         " is empty.")

def post_review(diff_file):
    """ Post a review request with the given diff_file.

    Raises:
        IOError if failed to run a git command.
        ValueError if the diff file is empty or doesn't exit.
    """
    _assert_diff_file(diff_file)
    # post-review -o --target-groups=tech "--diff-filename=${DIFF_FILE}"
    cmd = POST_REVIEW_TECH + ["--diff-filename={0}".format(diff_file)]
    rc = subprocess.call(cmd, stdout=None, stderr=None)
    if rc != 0:
        raise IOError("Failed to run a git command - " + str(cmd))


def post_snapshot(diff_file, review_id):
    """ Post a snapshot of an existing review request with the given diff_file.

    Raises:
        IOError if failed to run a git command.
        ValueError if the diff file is empty or doesn't exit.
    """
    _assert_diff_file(diff_file)
    # post-review --diff-only -r "${REVIEW_ID}" "--diff-filename=${DIFF_FILE}"
    cmd = POST_REVIEW_SNAPSHOT + ["-r", str(review_id)] +\
          ["--diff-filename={0}".format(diff_file)]
    rc = subprocess.call(cmd, stdout=None, stderr=None)
    if rc != 0:
        raise IOError("Failed to run a git command - " + str(cmd))


def ask_yes_no_question(message):
    """ Ask a yes-no question and return True for yes."""
    choice = raw_input(message + " [Y/N]? ")
    return choice.lower() == 'y'
