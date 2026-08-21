package com.itheima.model.command;

public enum ContentType {
    VIDEO(1),
    POST(2);

    final int typeNumber;

    ContentType(int typeNumber) {
        this.typeNumber = typeNumber;
    }

    public int getTypeNumber() {
        return typeNumber;
    }
}
