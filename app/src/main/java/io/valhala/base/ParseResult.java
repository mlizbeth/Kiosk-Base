package io.valhala.base;


public class ParseResult
{
    public int errorCode;
    public String data;

    public ParseResult(int errorCode, String data)
    {
        this.errorCode = errorCode;
        this.data = data;
    }
}

