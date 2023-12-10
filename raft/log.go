package raft

import (
	"fmt"
	"sync"
	"sync/atomic"
)

type Entry struct {
	Term    int
	Command interface{}
}
type Log struct {
	Entries       []Entry
	FirstLogIndex int
	LastLogIndex  int
}

func NewLog() *Log {
	return &Log{
		Entries:       make([]Entry, 0),
		FirstLogIndex: 1,
		LastLogIndex:  0,
	}
}
func (log *Log) getRealIndex(index int) int {
	return index - log.FirstLogIndex
}
func (log *Log) getOneEntry(index int) *Entry {
	return &log.Entries[log.getRealIndex(index)]
}

func (log *Log) appendL(newEntries ...Entry) {
	log.Entries = append(log.Entries[:log.getRealIndex(log.LastLogIndex)+1], newEntries...)
	log.LastLogIndex += len(newEntries)

}
func (log *Log) getAppendEntries(start int) []Entry {
	ret := append([]Entry{}, log.Entries[log.getRealIndex(start):log.getRealIndex(log.LastLogIndex)+1]...)
	return ret
}
func (log *Log) String() string {
	if log.empty() {
		return "logempty"
	}
	return fmt.Sprintf("%v", log.getAppendEntries(log.FirstLogIndex))
}
func (log *Log) empty() bool {
	return log.FirstLogIndex > log.LastLogIndex
}
func (rf *Raft) getEntryTerm(index int) int {
	if index == 0 {
		return 0
	}

	if rf.log.FirstLogIndex <= rf.log.LastLogIndex {
		return rf.log.getOneEntry(index).Term
	}

	return -1
}

type ApplyHelper struct {
	applyCh       chan ApplyMsg
	lastItemIndex int
	q             []ApplyMsg
	mu            sync.Mutex
	cond          *sync.Cond
	dead          int32
}

func NewApplyHelper(applyCh chan ApplyMsg, lastApplied int) *ApplyHelper {
	applyHelper := &ApplyHelper{
		applyCh:       applyCh,
		lastItemIndex: lastApplied,
		q:             make([]ApplyMsg, 0),
	}
	applyHelper.cond = sync.NewCond(&applyHelper.mu)
	go applyHelper.applier()
	return applyHelper
}
func (applyHelper *ApplyHelper) Kill() {
	atomic.StoreInt32(&applyHelper.dead, 1)
}
func (applyHelper *ApplyHelper) killed() bool {
	z := atomic.LoadInt32(&applyHelper.dead)
	return z == 1
}
func (applyHelper *ApplyHelper) applier() {
	for !applyHelper.killed() {
		applyHelper.mu.Lock()
		if len(applyHelper.q) == 0 {
			applyHelper.cond.Wait()
		}
		msg := applyHelper.q[0]
		applyHelper.q = applyHelper.q[1:]
		applyHelper.mu.Unlock()
		applyHelper.applyCh <- msg
	}
}
func (applyHelper *ApplyHelper) tryApply(msg *ApplyMsg) bool {
	applyHelper.mu.Lock()
	defer applyHelper.mu.Unlock()
	if msg.CommandValid {
		if msg.CommandIndex <= applyHelper.lastItemIndex {
			return true
		}
		if msg.CommandIndex == applyHelper.lastItemIndex+1 {
			applyHelper.q = append(applyHelper.q, *msg)
			applyHelper.lastItemIndex++
			applyHelper.cond.Broadcast()
			return true
		}
		panic("applyhelper meet false")
	} else if msg.SnapshotValid {
		if msg.SnapshotIndex <= applyHelper.lastItemIndex {
			return true
		}
		applyHelper.q = append(applyHelper.q, *msg)
		applyHelper.lastItemIndex = msg.SnapshotIndex
		applyHelper.cond.Broadcast()
		return true
	} else {
		panic("applyHelper meet both invalid")
	}
}

