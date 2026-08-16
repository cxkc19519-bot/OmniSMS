package model

import "time"

type MessageRequest struct {
	MessageID     string `json:"messageId"`
	Sender        string `json:"sender"`
	Body          string `json:"body"`
	ReceivedAt    string `json:"receivedAt"`
	SIMSlot       *int   `json:"simSlot"`
	SIMLabel      string `json:"simLabel"`
	QueuedOffline bool   `json:"queuedOffline"`
	AppVersion    string `json:"appVersion"`
}

type Delivery struct {
	DeviceID, MessageID, Sender, Body, SIMLabel, AppVersion string
	ReceivedAt, AcceptedAt                                  time.Time
	SIMSlot                                                 *int
	QueuedOffline                                           bool
	AttemptCount                                            int
}
