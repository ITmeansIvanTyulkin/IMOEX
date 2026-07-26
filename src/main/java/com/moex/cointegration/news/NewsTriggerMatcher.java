package com.moex.cointegration.news;

import com.moex.cointegration.model.NewsItem;
import com.moex.cointegration.model.NewsRiskLevel;
import com.moex.cointegration.model.NewsTriggerHit;
import com.moex.cointegration.model.NewsTriggerType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Сопоставляет заголовки новостей с тикером по правилам-триггерам (swing/daily, не tick-by-tick).
 * Порядок правил важен: более специфичные (CUT/MISS) раньше общих (дивиденд/прибыль).
 */
@Component
public class NewsTriggerMatcher {

    private record Rule(
            NewsTriggerType type,
            NewsRiskLevel severity,
            Pattern pattern,
            String explanation,
            boolean oftenAsymmetric
    ) {
    }

    private final List<Rule> rules = List.of(
            rule(NewsTriggerType.TRADING_HALT, NewsRiskLevel.BLOCK,
                    "приостановк\\p{L}*\\s+торг|торги\\s+приостанов|остановк\\p{L}*\\s+торг",
                    "Приостановка торгов — нельзя открывать парную сделку.", true),
            rule(NewsTriggerType.DELISTING, NewsRiskLevel.BLOCK,
                    "делистинг|исключ\\p{L}*\\s+из\\s+списка|снят\\p{L}*\\s+с\\s+торгов",
                    "Делистинг/исключение из списка ломает коинтеграцию.", true),
            rule(NewsTriggerType.SANCTIONS, NewsRiskLevel.BLOCK,
                    "санкц|sdn|блокирующ\\p{L}*\\s+санкц",
                    "Санкционный триггер — высокий риск асимметричного шока.", true),
            rule(NewsTriggerType.DEFAULT_BANKRUPTCY, NewsRiskLevel.BLOCK,
                    "банкрот|дефолт|несостоятельн",
                    "Банкротство/дефолт — mean-reversion недостоверен.", true),
            rule(NewsTriggerType.REORGANIZATION_MNA, NewsRiskLevel.BLOCK,
                    "реорганизац|поглощен|присоединен|слиян|m&a|обязательн\\p{L}*\\s+предложен",
                    "M&A/реорганизация меняет экономику одной ноги пары.", true),

            // Корпоративные HIGH/BLOCK — conflict с техсигналом
            rule(NewsTriggerType.EARNINGS_MISS, NewsRiskLevel.BLOCK,
                    "(прибыл|чист\\p{L}*\\s+прибыл|earnings|eps).{0,40}(ниже|сниз|упал|паден|убыт|miss|хуже\\s+ожидан)"
                            + "|(ниже|сниз|упал|паден|хуже\\s+ожидан).{0,40}(прибыл|earnings|eps)"
                            + "|убыток.{0,20}(квартал|отчет|отчёт)",
                    "Прибыль/отчёт хуже ожиданий — техника может врать до переоценки.", true),
            rule(NewsTriggerType.GUIDANCE_DOWN, NewsRiskLevel.BLOCK,
                    "(прогноз|guidance|outlook).{0,40}(сниз|пониз|ухудш|cut|down)"
                            + "|(сниз|пониз|ухудш).{0,40}(прогноз|guidance|outlook)"
                            + "|понизил\\p{L}*\\s+прогноз",
                    "Снижение прогноза компании — высокий риск слома спреда.", true),
            rule(NewsTriggerType.DIVIDEND_CUT, NewsRiskLevel.BLOCK,
                    "(дивиденд).{0,40}(сокра|сниз|отмен|урез|cut)"
                            + "|(сокра|сниз|отмен|урез).{0,40}(дивиденд)",
                    "Сокращение/отмена дивидендов бьёт по одной ноге пары.", true),
            rule(NewsTriggerType.SECONDARY_OFFERING, NewsRiskLevel.BLOCK,
                    "\\bspo\\b|допэмис|вторичн\\p{L}*\\s+размещ|дополнительн\\p{L}*\\s+выпуск\\p{L}*\\s+акц",
                    "Допэмиссия/SPO размывает и давит одну бумагу.", true),

            rule(NewsTriggerType.MANDATORY_OFFER, NewsRiskLevel.HIGH,
                    "оферт|обязательн\\p{L}*\\s+выкуп",
                    "Оферта/обязательный выкуп искажает цену одной ноги.", true),
            rule(NewsTriggerType.DISCRETE_AUCTION, NewsRiskLevel.HIGH,
                    "дискретн\\p{L}*\\s+аукцион",
                    "Дискретный аукцион — ликвидность и цена временно «ломаются».", true),

            rule(NewsTriggerType.EARNINGS_BEAT, NewsRiskLevel.MEDIUM,
                    "(прибыл|earnings|eps).{0,40}(выше|рост|вырос|beat|лучше\\s+ожидан)"
                            + "|(выше|рост|вырос|лучше\\s+ожидан).{0,40}(прибыл|earnings|eps)",
                    "Отчёт лучше ожиданий — слабое подтверждение техники.", true),
            rule(NewsTriggerType.GUIDANCE_UP, NewsRiskLevel.MEDIUM,
                    "(прогноз|guidance).{0,40}(повыс|улучш|up)"
                            + "|(повыс|улучш).{0,40}(прогноз|guidance)",
                    "Повышение прогноза — слабое подтверждение.", true),
            rule(NewsTriggerType.MAJOR_CONTRACT, NewsRiskLevel.MEDIUM,
                    "крупн\\p{L}*\\s+контракт|заключил\\p{L}*\\s+контракт|госзаказ|подписал\\p{L}*\\s+соглашен",
                    "Крупный контракт может сдвинуть справедливый уровень.", true),

            rule(NewsTriggerType.RISK_PARAMS_CHANGE, NewsRiskLevel.MEDIUM,
                    "риск-параметр|ценового\\s+коридора|дополнительн\\p{L}*\\s+мер\\p{L}*\\s+по\\s+противодействию",
                    "Изменение риск-параметров MOEX — caution по ликвидности/шорту.", true),
            rule(NewsTriggerType.DIVIDEND_EVENT, NewsRiskLevel.MEDIUM,
                    "дивиденд|cutoff|дата\\s+закрытия\\s+реестра",
                    "Дивидендное событие часто асимметрично для пары.", true),
            rule(NewsTriggerType.BUYBACK, NewsRiskLevel.MEDIUM,
                    "buyback|обратн\\p{L}*\\s+выкуп|программ\\p{L}*\\s+выкуп",
                    "Buyback может сдвинуть справедливый уровень спреда.", true),
            rule(NewsTriggerType.MANAGEMENT_CHANGE, NewsRiskLevel.MEDIUM,
                    "гендиректор|ceo|совет\\s+директор|отставк|назначен\\p{L}*\\s+президент",
                    "Смена менеджмента — возможен режимный сдвиг цены.", true)
    );

    public List<NewsTriggerHit> match(String ticker, String shortName, String secName, List<NewsItem> news) {
        List<NewsTriggerHit> hits = new ArrayList<>();
        for (NewsItem item : news) {
            if (!mentionsTicker(item.title(), ticker, shortName, secName)) {
                continue;
            }
            String lower = item.title().toLowerCase(Locale.ROOT);
            for (Rule rule : rules) {
                if (rule.pattern().matcher(lower).find()) {
                    hits.add(new NewsTriggerHit(
                            ticker,
                            rule.type(),
                            rule.severity(),
                            item.title(),
                            item.publishedAt(),
                            rule.explanation(),
                            rule.oftenAsymmetric()
                    ));
                    break; // один главный триггер на новость
                }
            }
        }
        return hits;
    }

    /** Новость относится к тикеру, если в заголовке есть тикер или короткое имя. */
    public boolean mentionsTicker(String title, String ticker, String shortName, String secName) {
        if (title == null || title.isBlank()) {
            return false;
        }
        String t = title.toUpperCase(Locale.ROOT);
        String code = ticker.toUpperCase(Locale.ROOT);
        if (containsToken(t, code)) {
            return true;
        }
        if (shortName != null && !shortName.isBlank()) {
            String sn = shortName.trim();
            if (sn.length() >= 3 && title.toLowerCase(Locale.ROOT).contains(sn.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        if (secName != null && !secName.isBlank()) {
            String first = secName.split("\\s+")[0];
            if (first.length() >= 4 && title.toLowerCase(Locale.ROOT).contains(first.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private boolean containsToken(String upperTitle, String ticker) {
        String padded = " " + upperTitle.replaceAll("[^A-ZА-Я0-9]+", " ") + " ";
        return padded.contains(" " + ticker + " ");
    }

    private static Rule rule(
            NewsTriggerType type,
            NewsRiskLevel severity,
            String regex,
            String explanation,
            boolean asymmetric
    ) {
        return new Rule(type, severity, Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE),
                explanation, asymmetric);
    }
}
