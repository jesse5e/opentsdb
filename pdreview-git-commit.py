#!/usr/bin/python -u
"""Copyright (C) 2013 Pepperdata Inc. - All rights reserved.

pdreview-git-commit - take the summary of the change from reviewboard
and generate a git-commit message

"""
import json
import optparse
import os
import re
import subprocess
import sys
import tempfile
import textwrap
import urllib2

from pdreview_git_tools import assert_git_tree
from pdreview_git_tools import assert_no_incoming_changes
from pdreview_git_tools import assert_no_uncached_diffs

def read_cookie():
    """Reads the users review board cookie.

    Returns:
        The cookie value that can be passed in review board APIs."""

    path = os.path.expanduser('~/.post-review-cookies.txt')
    with open(path, 'r') as file:
        content = file.read()
    m = re.search("(rbsessionid\t.*)[\t\n$]", content)
    try:
        cookie = m.group(1).replace('\t', '=')
    except IndexError, e:
        raise IOError("Can't find rbsessionid in " + path)
    return cookie


def parse_json_single_result(json_summary):
    """Parse the json returned from the review board API.

    This parses the result of a single review fetch.

    Returns:
        The tuple of summary and description fields. """

    json_obj = json.loads(json_summary)
    status = json_obj["stat"]
    if status != "ok":
        raise IOError("Status is not ok: " + status)
    rr = json_obj["review_request"]
    summary = rr["summary"].strip()
    description = rr["description"].strip()
    lines = textwrap.wrap(description, 80)
    description = "\n".join(lines)
    return (summary, description)


def parse_json_multi_results(json_summary):
    """Parse the json returned from the review board API.

    This parses the result of a multi-review fetch.
    Returns:
        A list of tuples of review id, submitter, and summary fields."""

    json_obj = json.loads(json_summary)
    status = json_obj["stat"]
    if status != "ok":
        raise IOError("Status is not ok: " + status)
    rrs = json_obj["review_requests"]
    reviews = []
    for rr in rrs:
        idnum = rr["id"]
        submitter = rr["links"]["submitter"]["title"].strip()
        summary = rr["summary"].strip()
        reviews.append((idnum, submitter, summary))
    return reviews


def get_api_url(review_num):
    """Gets the API URL for a review number. """

    return "https://rbcommons.com/s/pepperdata/api/review-requests/{0}/" \
            .format(review_num)


def fetch_review(url, cookie):
    """Makes call to review board API.

    Returns:
        The json encoded summary of the given api uri. """

    req = urllib2.Request(url)
    req.add_header('Cookie', cookie)
    r = urllib2.urlopen(req)
    return r.read()


def mark_submitted(url, cookie, description=None):
    """Marks a review as submitted.

    Returns:
        The json encoded result of the request. """

    data = 'status=submitted'
    if description:
        print "Setting description to: {0}".format(description)
        data += ';description={0}'.format(description)
    req = urllib2.Request(url, data)
    req.add_header('Cookie', cookie)
    req.get_method = lambda : 'PUT'
    r = urllib2.urlopen(req)
    return r.read()


def get_editor():
    """Tries to get editor from env variables.

    Returns:
        Prefered editor if found, otherwise, vim."""

    editor = os.environ.get('VISUAL')
    if editor != None:
        return editor
    editor = os.environ.get('EDITOR')
    if editor != None:
        return editor;
    return "vim"


def print_available_reviews():
    """Prints a list of pending reviews to stdout."""
    cookie = read_cookie()
    uri = "https://rbcommons.com/s/pepperdata/api/review-requests/" \
            "?status=pending"
    json_result = fetch_review(uri, cookie)
    reviews = parse_json_multi_results(json_result)
    for review in sorted(reviews):
        print "{0:4} {1:10} {2} ".format(review[0], review[1], review[2])


def get_git_last_log():
    """Return the most recent last log."""
    p = subprocess.Popen(["git", "log", "--shortstat", "--format=oneline",
                          "-n", "1"], stdout=subprocess.PIPE)
    (out, _) = p.communicate()
    if p.returncode != 0:
        raise IOError("Can't get the last log.")
    return "changeset: {0}".format(out.strip())


def main(argv = None):
    """Main function that can be called interactively.

    Coordinates all the events in commit:  retrieving the summary,
    allowing user to see/edit it, then commit with ask.

    Returns:
        Value to return to shell. """

    parser = optparse.OptionParser()
    parser.add_option('-d', '--dry', action='store_true', dest='dryrun',
                      default=False, help="Only print what would happen")
    parser.add_option('-n', '--nopush', action='store_true', dest='nopush',
                      default=False, help="Refrain from pushing after commit")
    parser.add_option('-l', '--list', action='store_true', dest='list',
                      default=False, help="List pending reviews")
    parser.add_option('--skip-commit', action='store_true',
                      help="Do not commit before push")
    (options, args) = parser.parse_args(argv)
    assert_git_tree()
    if options.list:
        print "Pending reviews:"
        print_available_reviews()
        return 0
    if len(args) != 1:
        print "Please enter review number; use --list to see pending reviews"
        return 1
    options.commit = not options.skip_commit
    review_num = args[0]
    print "Using description and summary from review " + review_num
    (file, fname) = (None, None)
    rc = 0
    try:
        cookie = read_cookie()
        url = get_api_url(review_num)
        review = fetch_review(url, cookie)
        (summary, description) = parse_json_single_result(review)
        link = '(https://rbcommons.com/s/pepperdata/r/{0})'.format(review_num)
        msg = summary + " " + link + '\n' + description
        (file, fname) = tempfile.mkstemp(prefix='pdreview-commit-',
                suffix='.txt')
        os.write(file, msg)
        os.close(file)
        file = None
        done = False
        while not done:
            print "Setting commit message to:\n"
            with open(fname, 'r') as f:
                content = f.read()
            print content
            print "\n"
            choice = raw_input("OK to submit?  y=submit e=edit ")
            if choice.lower() == 'e':
                editor = get_editor()
                rc = subprocess.call(editor + " " + fname, shell=True)
                if rc != 0:
                    raise IOError("Can't edit summary, editor returned " + rc);
                continue
            if options.dryrun:
                print "Dry run done"
                done = True
                break
            if choice.lower() == 'y':
                if options.commit:
                    print "Committing"
                    assert_no_uncached_diffs()
                    assert_no_incoming_changes()
                    rc = subprocess.call("git commit --file " + fname,
                                         shell=True)
                else:
                    print "Pushing without commit"
                if rc == 0 and not options.nopush:
                    print "Pushing"
                    assert_no_uncached_diffs()
                    rc = subprocess.call("git push", shell=True)
                    if rc == 0:
                        print "Marking as submitted"
                        description = get_git_last_log()
                        mark_submitted(url, cookie, description)
                done = True
            else:
                print "Skipping commit"
                done = True
    except IOError, e:
        print "Error: " + str(e)
        rc = 2
    finally:
        if file != None:
            print file
            os.close(file)
        if fname != None:
            os.unlink(fname)
    return rc


if __name__ == "__main__":
    # TODO(jesse): Combine with other pdreview-git commands.
    # TODO(jesse): Share code with pdreview scripts for mercurial.
    sys.exit(main())
