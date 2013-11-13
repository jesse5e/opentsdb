#!/bin/sh
#
# Modified from:
#   https://github.com/OpenTSDB/opentsdb/blob/v2.0.0RC2/build-aux/deb/init.d/opentsdb.
#   https://github.com/masiulaniec/opentsdb-rhel/blob/master/src/tsdb-server.init

. /lib/lsb/init-functions

NAME=opentsdb

# Maximum number of open files
MAX_OPEN_FILES=65535

PROG=/usr/bin/tsdb
PROG_OPTS=tsd
HOSTNAME=$(hostname --fqdn)

LOG_DIR=/var/log/$NAME
LOG_FILE_PREFIX=${LOG_DIR}/${HOSTNAME}-opentsdb
[ -e $LOG_DIR ] || mkdir $LOG_DIR

[ -e /etc/sysconfig/$NAME ] && . /etc/sysconfig/$NAME

start() {
  status >/dev/null && return 0
  echo "Starting ${NAME}.... See logs in ${LOG_DIR}."

  ulimit -n $MAX_OPEN_FILES

  # TODO: The tsdb program does not run in background. Make it happen.
  # TODO: Support non-root user and group.
  $PROG $PROG_OPTS 1> ${LOG_FILE_PREFIX}.out 2> ${LOG_FILE_PREFIX}.err &
}

stop() {
  status >/dev/null || return 0
  echo "Stopping ${NAME}..."
  f=$(mktemp /tmp/pidfile.XXXXXXX)
  findproc >$f
  killproc -p $f > /dev/null
  rm -f $f
}

restart() {
    stop
    start
}

reload() {
    restart
}

force_reload() {
    restart
}

rh_status() {
    # run checks to determine if the service is running or use generic status
    status
}

rh_status_q() {
    rh_status >/dev/null 2>&1
}

findproc() {
    pgrep -f '^java .* net.opentsdb.tools.TSDMain'
}

status() {
    pid=$(findproc)
    if [ -n "$pid" ]
    then
        echo "${NAME} is running... (pid $pid)"
        return 0
    else
        echo "${NAME} is stopped."
        return 1
    fi
}


case "$1" in
    start)
        rh_status_q && exit 0
        $1
        ;;
    stop)
        rh_status_q || exit 0
        $1
        ;;
    restart)
        $1
        ;;
    reload)
        rh_status_q || exit 7
        $1
        ;;
    force-reload)
        force_reload
        ;;
    status)
        rh_status
        ;;
    condrestart|try-restart)
        rh_status_q || exit 0
        restart
        ;;
    *)
        echo $"Usage: $0 {start|stop|status|restart|condrestart|try-restart|reload|force-reload}"
        exit 2
esac
exit $?
