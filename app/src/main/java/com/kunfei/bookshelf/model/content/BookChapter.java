package com.kunfei.bookshelf.model.content;

import android.text.TextUtils;

import com.kunfei.bookshelf.MApplication;
import com.kunfei.bookshelf.R;
import com.kunfei.bookshelf.base.BaseModelImpl;
import com.kunfei.bookshelf.bean.BookShelfBean;
import com.kunfei.bookshelf.bean.BookSourceBean;
import com.kunfei.bookshelf.bean.ChapterListBean;
import com.kunfei.bookshelf.bean.WebChapterBean;
import com.kunfei.bookshelf.model.analyzeRule.AnalyzeRule;
import com.kunfei.bookshelf.model.analyzeRule.AnalyzeUrl;
import com.kunfei.bookshelf.model.task.AnalyzeNextUrlTask;

import org.jsoup.nodes.Element;
import org.mozilla.javascript.NativeObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.reactivex.Emitter;
import io.reactivex.Observable;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.disposables.Disposable;
import retrofit2.Response;

public class BookChapter {
    private String tag;
    private BookSourceBean bookSourceBean;
    private AnalyzeRule analyzer;
    private List<WebChapterBean> webChapterBeans;
    private boolean dx = false;
    private boolean analyzeNextUrl;
    private CompositeDisposable compositeDisposable;

    BookChapter(String tag, BookSourceBean bookSourceBean, boolean analyzeNextUrl) {
        this.tag = tag;
        this.bookSourceBean = bookSourceBean;
        this.analyzeNextUrl = analyzeNextUrl;
    }

    public Observable<List<ChapterListBean>> analyzeChapterList(final String s, final BookShelfBean bookShelfBean, Map<String, String> headerMap) {
        return Observable.create(e -> {
            if (TextUtils.isEmpty(s)) {
                e.onError(new Throwable(MApplication.getInstance().getString(R.string.get_chapter_list_error) + bookShelfBean.getBookInfoBean().getChapterUrl()));
                return;
            } else {
                Debug.printLog(tag, "┌成功获取目录页", analyzeNextUrl);
                Debug.printLog(tag, "└" + bookShelfBean.getBookInfoBean().getChapterUrl(), analyzeNextUrl);
            }
            bookShelfBean.setTag(tag);
            analyzer = new AnalyzeRule(bookShelfBean);
            String ruleChapterList = bookSourceBean.getRuleChapterList();
            if (ruleChapterList != null && ruleChapterList.startsWith("-")) {
                dx = true;
                ruleChapterList = ruleChapterList.substring(1);
            }
            WebChapterBean webChapterBean = analyzeChapterList(s, bookShelfBean.getBookInfoBean().getChapterUrl(), ruleChapterList, analyzeNextUrl);
            final List<ChapterListBean> chapterList = webChapterBean.getData();

            List<String> chapterUrlS = new ArrayList<>(webChapterBean.getNextUrlList());
            if (chapterUrlS.isEmpty() || !analyzeNextUrl) {
                finish(chapterList, e);
            }
            //下一页为单页
            else if (chapterUrlS.size() == 1) {
                List<String> usedUrl = new ArrayList<>();
                usedUrl.add(bookShelfBean.getBookInfoBean().getChapterUrl());
                //循环获取直到下一页为空
                while (!chapterUrlS.isEmpty() && !usedUrl.contains(chapterUrlS.get(0))) {
                    Debug.printLog(tag, "正在加载下一页");
                    usedUrl.add(chapterUrlS.get(0));
                    AnalyzeUrl analyzeUrl = new AnalyzeUrl(chapterUrlS.get(0), headerMap, tag);
                    try {
                        String body;
                        Response<String> response = BaseModelImpl.getInstance().getResponseO(analyzeUrl)
                                .blockingFirst();
                        body = response.body();
                        Debug.printLog(tag, "正在解析下一页");
                        webChapterBean = analyzeChapterList(body, chapterUrlS.get(0), ruleChapterList, false);
                        chapterUrlS = new ArrayList<>(webChapterBean.getNextUrlList());
                        chapterList.addAll(webChapterBean.getData());
                    } catch (Exception exception) {
                        if (!e.isDisposed()) {
                            e.onError(exception);
                        }
                    }
                }
                finish(chapterList, e);
            }
            //下一页为多页
            else {
                Debug.printLog(tag, "正在加载其它" + chapterUrlS.size() + "页");
                compositeDisposable = new CompositeDisposable();
                webChapterBeans = new ArrayList<>();
                AnalyzeNextUrlTask.Callback callback = new AnalyzeNextUrlTask.Callback() {
                    @Override
                    public void addDisposable(Disposable disposable) {
                        compositeDisposable.add(disposable);
                    }

                    @Override
                    public void analyzeFinish(WebChapterBean bean, List<ChapterListBean> chapterListBeans) {
                        if (nextUrlFinish(bean, chapterListBeans)) {
                            for (WebChapterBean chapterBean : webChapterBeans) {
                                chapterList.addAll(chapterBean.getData());
                            }
                            Debug.printLog(tag, "其它页加载完成,目录共" + chapterList.size() + "条");
                            finish(chapterList, e);
                        }
                    }

                    @Override
                    public void onError(Throwable throwable) {
                        compositeDisposable.dispose();
                        e.onError(throwable);
                    }
                };
                for (String url : chapterUrlS) {
                    final WebChapterBean bean = new WebChapterBean(url);
                    webChapterBeans.add(bean);
                }
                for (WebChapterBean bean : webChapterBeans) {
                    BookChapter bookChapter = new BookChapter(tag, bookSourceBean, false);
                    AnalyzeUrl analyzeUrl = new AnalyzeUrl(bean.getUrl(), headerMap, tag);
                    new AnalyzeNextUrlTask(bookChapter, bean, bookShelfBean, headerMap)
                            .setCallback(callback)
                            .analyzeUrl(analyzeUrl);
                }
            }
        });
    }

    private synchronized boolean nextUrlFinish(WebChapterBean webChapterBean, List<ChapterListBean> chapterListBeans) {
        webChapterBean.setData(chapterListBeans);
        for (WebChapterBean bean : webChapterBeans) {
            if (bean.noData()) return false;
        }
        return true;
    }

    private void finish(List<ChapterListBean> chapterList, Emitter<List<ChapterListBean>> emitter) {
        //去除重复,保留后面的,先倒序,从后面往前判断
        if (!dx) {
            Collections.reverse(chapterList);
        }
        LinkedHashSet<ChapterListBean> lh = new LinkedHashSet<>(chapterList);
        chapterList = new ArrayList<>(lh);
        Collections.reverse(chapterList);
        Debug.printLog(tag, "-目录解析完成", analyzeNextUrl);
        emitter.onNext(chapterList);
        emitter.onComplete();
    }

    // 纯java模式正则表达式获取目录列表
    private List<ChapterListBean> Reger(String str, String[] regex, int index, int s1, int s2, List<ChapterListBean> chapterBeans)
    {
        Matcher m = Pattern.compile(regex[index]).matcher(str);
        if(index + 1 == regex.length){
            while(m.find()){
                chapterBeans.add(new ChapterListBean(tag, m.group(s1), m.group(s2)));
            }
            return chapterBeans;
        }
        else{
            StringBuilder result = new StringBuilder();
            while (m.find()) result.append(m.group());
            return Reger(result.toString(), regex, ++index, s1, s2, chapterBeans);
        }
    }

    private WebChapterBean analyzeChapterList(String s, String chapterUrl, String ruleChapterList, boolean printLog) throws Exception {
        List<String> nextUrlList = new ArrayList<>();
        analyzer.setContent(s, chapterUrl);
        if (!TextUtils.isEmpty(bookSourceBean.getRuleChapterUrlNext()) && analyzeNextUrl) {
            Debug.printLog(tag, "┌获取目录下一页网址", printLog);
            nextUrlList = analyzer.getStringList(bookSourceBean.getRuleChapterUrlNext(), true);
            int thisUrlIndex = nextUrlList.indexOf(chapterUrl);
            if (thisUrlIndex != -1) {
                nextUrlList.remove(thisUrlIndex);
            }
            Debug.printLog(tag, "└" + nextUrlList.toString(), printLog);
        }

        List<ChapterListBean> chapterBeans = new ArrayList<>();
        Debug.printLog(tag, "┌解析目录列表", printLog);
        // 仅使用java正则表达式提取目录列表
        if(ruleChapterList.startsWith("J$")){
            ruleChapterList = ruleChapterList.substring(2);
            chapterBeans = Reger(s, ruleChapterList.split("&&"),0,
                    Integer.parseInt(bookSourceBean.getRuleChapterName()),
                    Integer.parseInt(bookSourceBean.getRuleContentUrl()),
                    chapterBeans
            );
            if (chapterBeans.size() == 0){
                Debug.printLog(tag, "└找到 0 个章节", printLog);
                return new WebChapterBean(chapterBeans, new LinkedHashSet<>(nextUrlList));
            }
        }
        // 使用AllInOne规则模式提取目录列表
        else if (ruleChapterList.startsWith("+")){
            ruleChapterList = ruleChapterList.substring(1);
            List<Object> collections = analyzer.getElements(ruleChapterList);
            if (collections.size() == 0){
                Debug.printLog(tag, "└找到 0 个章节", printLog);
                return new WebChapterBean(chapterBeans, new LinkedHashSet<>(nextUrlList));
            }
            String nameRule = bookSourceBean.getRuleChapterName();
            String linkRule = bookSourceBean.getRuleContentUrl();
            String name = "";
            String link = "";
            for (Object object: collections) {
                if(object instanceof NativeObject){
                    name = String.valueOf(((NativeObject)object).get(nameRule));
                    link = String.valueOf(((NativeObject)object).get(linkRule));
                } else if(object instanceof Element){
                    name = ((Element)object).text();
                    link = ((Element)object).attr(linkRule);
                }
                chapterBeans.add(new ChapterListBean(tag, name, link));
            }
        }
        // 使用默认规则解析流程提取目录列表
        else{
            List<Object> collections = analyzer.getElements(ruleChapterList);
            if (collections.size() == 0){
                Debug.printLog(tag, "└找到 0 个章节", printLog);
                return new WebChapterBean(chapterBeans, new LinkedHashSet<>(nextUrlList));
            }
            List<AnalyzeRule.SourceRule> nameRule = analyzer.splitSourceRule(bookSourceBean.getRuleChapterName());
            List<AnalyzeRule.SourceRule> linkRule = analyzer.splitSourceRule(bookSourceBean.getRuleContentUrl());
            for (Object object: collections) {
                analyzer.setContent(object, chapterUrl);
                chapterBeans.add(new ChapterListBean(
                        tag,
                        analyzer.getString(nameRule),
                        analyzer.getString(linkRule)
                ));
            }
        }
        Debug.printLog(tag, "└找到 " + chapterBeans.size() + " 个章节", printLog);
        ChapterListBean firstChapter = chapterBeans.get(0);
        Debug.printLog(tag, "┌获取章节名称", printLog);
        Debug.printLog(tag, "└" + firstChapter.getDurChapterName(), printLog);
        Debug.printLog(tag, "┌获取章节网址", printLog);
        Debug.printLog(tag, "└" + firstChapter.getDurChapterUrl(), printLog);
        return new WebChapterBean(chapterBeans, new LinkedHashSet<>(nextUrlList));
    }

}